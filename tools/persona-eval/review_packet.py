#!/usr/bin/env python3
"""Create an offline blinded review packet; never assign subjective scores."""

import argparse
import base64
from collections import Counter
import hashlib
import html
import json
from pathlib import Path
import secrets

from evaluate import suite_digest
from schema import VERSION, load_suite, require


def normalize(report):
    if report.get("kind") != "actual_cloud_subjective_probe":
        return report
    require(report.get("case_id") == "heldout-12" and report.get("split") == "heldout",
            "Only the separately executed cloud fixture is supported here")
    require(report.get("comparison_mode", "single_configuration") == "single_configuration",
            "Invalid cloud comparison mode")
    return dict(report, comparison_mode="single_configuration",
                config_snapshots={"candidate": {key: report.get(key) for key in
                    ("model_id", "provider_revision", "parameters", "backend_url", "source_revision")}},
                records=[dict(row, arm="candidate") for row in report.get("records", [])])


def validate(report):
    require(report.get("kind") in {"actual_mnn_subjective_batch", "actual_cloud_subjective_probe"}, "Not an actual-model run")
    require(report.get("dataset_version") == VERSION and report.get("dataset_sha256") == suite_digest(),
            "Dataset receipt mismatch")
    require(report.get("synthetic_data_only") is True, "Only synthetic output is allowed")
    require(report.get("split") in {"dev", "heldout"}, "Invalid split")
    require(report.get("execution_status") == "completed", "Batch is incomplete")
    single = report["kind"] == "actual_cloud_subjective_probe"
    mode = "single_configuration" if single else "blind_pairwise"
    require(report.get("comparison_mode", "blind_pairwise") == mode, "Invalid report comparison mode")
    require(not single or report.get("case_id") == "heldout-12" and report["split"] == "heldout",
            "Invalid cloud case scope")
    arms = {"candidate"} if single else {"baseline", "candidate"}
    require(set(report.get("config_snapshots", {})) == arms, "Missing configuration arm")
    cases = {case["id"]: case for case in load_suite()
             if case["track"] == "llm_subjective" and case["split"] == report["split"]
             and (not single or case["id"] == "heldout-12")}
    require(not single or report["split"] == "heldout", "Invalid single-configuration scope")
    expected = {(case, arm, repetition) for case in cases for arm in arms
                for repetition in (1, 2, 3)}
    rows = {}
    for row in report.get("records", []):
        key = (row.get("case_id"), row.get("arm"), row.get("repetition"))
        require(type(row.get("repetition")) is int and key in expected and key not in rows, "Unexpected or duplicate row")
        require(row.get("status") in {"completed", "failed", "unsupported"}, "Invalid execution status")
        response = row.get("response")
        require((isinstance(response, str) and bool(response.strip())) if row["status"] == "completed"
                else response is None, "Response/status mismatch")
        refs = row.get("authorized_evidence_refs")
        available = {note["ref"] for note in cases[key[0]]["memories"]}
        require(isinstance(refs, list) and all(isinstance(ref, str) for ref in refs)
                and len(refs) == len(set(refs)) and set(refs) <= available, "Unknown evidence reference")
        rows[key] = row
    require(set(rows) == expected, "Missing model rows; do not discard failures")
    return cases, rows


def create_packet(report_path, output):
    raw = Path(report_path).read_bytes()
    report = normalize(json.loads(raw))
    cases, rows = validate(report)
    single = report.get("comparison_mode") == "single_configuration"
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    packet_id = secrets.token_hex(16)
    mapping, pairs, cards = [], [], []
    esc = lambda value: html.escape(str(value), quote=True)
    for case_id, case in cases.items():
        for repetition in (1, 2, 3):
            arms = ["candidate"] if single else ["baseline", "candidate"]
            secrets.SystemRandom().shuffle(arms)
            labels = ("A",) if single else ("A", "B")
            pair_id = f"{case_id}-r{repetition}"
            mapping.append(dict(pair_id=pair_id, **dict(zip(labels, arms))))
            pair = {"pair_id": pair_id, "case_id": case_id, "repetition": repetition,
                    "reviewable": any(rows[(case_id, arm, repetition)]["status"] != "unsupported" for arm in arms),
                    "human_reviewer": None, "winner": None, "rationale": None,
                    "must_checks": {arm: {text: None for text in case["rubric"]["must"]} for arm in labels},
                    "must_not_violations": {arm: {text: None for text in case["rubric"]["must_not"]} for arm in labels},
                    "dimension_scores": {arm: {text: None for text in case["rubric"]["dimensions"]} for arm in labels},
                    "second_reviewer": None}
            pairs.append(pair)
            body = f'<section><h2>{esc(pair_id)} · {esc(case["title"])}</h2>'
            # M5LlmFixtures reads name/backstory; M5ActualChatRound inserts no traits.
            profile = case["persona_profile"]
            actual = {"name": profile["name"], "backstory": profile["personaBackstory"], "traits": []}
            body += '<h3>实际生成 Persona</h3><pre class="generation-persona">'
            body += esc(json.dumps(actual, ensure_ascii=False, indent=2)) + '</pre>'
            body += '<p>persona_profile 的 language/style/fiction 等元数据未作为独立字段传入模型；traits 实际为空。</p>'
            body += "<h3>对话</h3><pre>" + esc("\n".join(f'{m["role"]}: {m["content"]}' for m in case["messages"])) + "</pre>"
            body += "<p>必须：" + esc("；".join(case["rubric"]["must"])) + "</p>"
            body += "<p>不得：" + esc("；".join(case["rubric"]["must_not"])) + "</p><div class=pair>"
            for label, arm in zip(labels, arms):
                row = rows[(case_id, arm, repetition)]
                authorized = set(row["authorized_evidence_refs"])
                facts = [note["content"] for note in case["memories"] if note["ref"] in authorized]
                body += f'<article><h3>{label} · {esc(row["status"])}</h3><p>本次授权证据：</p><pre>{esc(chr(10).join(facts) or "（无）")}</pre>'
                text = row.get("response") or row.get("partial_response") or row.get("reason") or row.get("failure_class") or "无输出"
                body += f'<p>输出（失败时仅供诊断，不视为完整回答）：</p><pre>{esc(text)}</pre></article>'
            choices = "单配置检查，不产生胜过基线的结论" if single else "A / B / tie / both_fail / unresolved"
            body += f'</div><p>本组 {esc(pair_id)}：{choices}，逐项检查并给 0–2 分。</p></section>'
            cards.append(body)
    receipt = {"protocol": "human-pairwise-v1", "packet_id": packet_id,
               "input_sha256": hashlib.sha256(raw).hexdigest(),
               "split": report["split"], "status": "pending_human_review", "cases": pairs,
               "comparison_mode": "single_configuration" if single else "blind_pairwise",
               "execution_counts": dict(Counter(row["status"] for row in rows.values())),
               "summary": {"reviewed_pairs": 0, "release_decision": "pending_human_review"}}
    private_mapping = {"packet_id": packet_id, "mapping": mapping}
    for name, value in (("human-review.json", receipt), ("private-mapping.json", private_mapping),
                        ("private-configs.json", report["config_snapshots"])):
        (output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    script = (Path(__file__).parent / "review_form.js").read_text(encoding="utf-8")
    script_hash = base64.b64encode(hashlib.sha256(script.encode("utf-8")).digest()).decode("ascii")
    embedded = json.dumps(receipt, ensure_ascii=True).replace("<", "\\u003c")
    page = ('<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
            f'<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'; script-src \'sha256-{script_hash}\'">'
            '<title>Persona 人工盲评</title><style>body{max-width:1100px;margin:2rem auto;padding:1rem;font:16px/1.7 system-ui;background:#f3f5f8;color:#182331}'
            'section{background:white;padding:1.5rem;margin:1rem 0;border-radius:12px}.pair{display:grid;grid-template-columns:1fr 1fr;gap:1rem}'
            'article{border:1px solid #ccd4df;padding:1rem;min-width:0}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}'
            '@media(max-width:650px){.pair{grid-template-columns:1fr}}</style><h1>Persona 合成用例 · 人工盲评</h1>'
            '<p>仅包含真实运行导出的合成输出。请勿先打开 private-mapping.json 或 private-configs.json，以保留盲评。'
            '失败与 unsupported 均保留；生成成功不代表内容合格。可展开评分表并下载 JSON，或编辑同目录 human-review.json。'
            '本页面没有网络请求，也不会自动作出质量或发布结论。' +
            ('本页仅有一个云端配置，不能据此比较模型优劣。' if single else '') + '</p>' + "".join(cards) +
            '<script type="application/json" id="review-data">' + embedded + '</script><script>' + script + '</script></html>')
    (output / "review.html").write_text(page, encoding="utf-8")
    return {"pairs": 0 if single else len(pairs), "items": len(pairs),
            "execution_counts": receipt["execution_counts"], "human_review": "pending"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(create_packet(args.input, args.output), ensure_ascii=True))


if __name__ == "__main__":
    main()

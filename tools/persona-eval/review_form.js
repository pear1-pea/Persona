(() => {
  'use strict';
  const review = JSON.parse(document.getElementById('review-data').textContent);
  const key = review.packet_id ? 'persona-human-review-' + review.packet_id : null;
  try {
    const saved = key && JSON.parse(localStorage.getItem(key));
    if (saved && saved.packet_id === review.packet_id && saved.input_sha256 === review.input_sha256 &&
        saved.comparison_mode === review.comparison_mode && Array.isArray(saved.cases) &&
        saved.cases.length === review.cases.length && saved.cases.every((pair, index) => {
          const expected = review.cases[index];
          return pair && pair.pair_id === expected.pair_id && pair.case_id === expected.case_id &&
            pair.repetition === expected.repetition && pair.reviewable === expected.reviewable;
        })) {
      review.cases = saved.cases;
    }
  } catch (_) { /* File previews may disable local storage. */ }
  function save() {
    if (!key) return;
    try { localStorage.setItem(key, JSON.stringify(review)); } catch (_) { /* Download remains available. */ }
  }
  function select(parent, label, values, current, change) {
    const row = document.createElement('label');
    row.append(document.createTextNode(label + ' '));
    const input = document.createElement('select');
    for (const [value, title] of [['', '待评'], ...values]) {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = title;
      input.append(option);
    }
    input.value = current == null ? '' : String(current);
    input.addEventListener('change', () => { change(input.value); save(); });
    row.append(input);
    parent.append(row, document.createElement('br'));
  }
  const sections = document.querySelectorAll('section');
  review.cases.forEach((pair, index) => {
    if (pair.reviewable === false) {
      const message = document.createElement('p');
      message.textContent = '本组未执行真实模型，保留 unsupported 诊断，不能填写质量评分。';
      sections[index].append(message);
      return;
    }
    const form = document.createElement('details');
    const title = document.createElement('summary');
    title.textContent = '填写本组人工评分';
    form.append(title);
    for (const arm of Object.keys(pair.dimension_scores)) {
      const heading = document.createElement('h3');
      heading.textContent = arm;
      form.append(heading);
      for (const [field, criteria] of Object.entries({
        must_checks: pair.must_checks[arm], must_not_violations: pair.must_not_violations[arm]
      })) {
        for (const [criterion, current] of Object.entries(criteria)) {
          const violation = field === 'must_not_violations';
          select(form, (violation ? '是否违规：' : '是否满足：') + criterion,
            [['true', '是'], ['false', '否']], current,
            value => { pair[field][arm][criterion] = value === '' ? null : value === 'true'; });
        }
      }
      for (const [criterion, current] of Object.entries(pair.dimension_scores[arm])) {
        select(form, criterion, [['0', '0 明显失败'], ['1', '1 部分达到'], ['2', '2 达到要求']],
          current, value => { pair.dimension_scores[arm][criterion] = value === '' ? null : Number(value); });
      }
    }
    const choices = review.comparison_mode === 'single_configuration' ?
      [['A', '本次达到要求'], ['fail', '本次不合格'], ['unresolved', '无法判断，待复核']] :
      [['A', 'A 更好'], ['B', 'B 更好'], ['tie', '相当'],
        ['both_fail', '两边均不合格，结论明确'], ['unresolved', '无法判断，待复核']];
    select(form, '人工选择', choices,
      pair.winner, value => { pair.winner = value || null; });
    for (const [field, label] of [['human_reviewer', '评审者'], ['rationale', '理由'], ['second_reviewer', '关键违规或分歧的复核者']]) {
      const row = document.createElement('label');
      row.textContent = label + ' ';
      const input = document.createElement(field === 'rationale' ? 'textarea' : 'input');
      input.value = pair[field] || '';
      input.addEventListener('input', () => { pair[field] = input.value.trim() || null; save(); });
      row.append(input);
      form.append(row, document.createElement('br'));
    }
    sections[index].append(form);
  });
  const button = document.createElement('button');
  button.textContent = '下载人工评分 JSON（可分次填写）';
  button.addEventListener('click', () => {
    review.status = 'human_annotations_exported_requires_validation';
    review.summary.release_decision = 'pending_human_review';
    save();
    const url = URL.createObjectURL(new Blob([JSON.stringify(review, null, 2)], {type: 'application/json'}));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'human-review-' + review.split + '.json';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
  document.body.insertBefore(button, document.querySelector('section'));
})();

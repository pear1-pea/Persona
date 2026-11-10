# Protected master

Master requires a pull request and up-to-date passing checks from the GitHub Actions
app (ID 15368). The policy applies to administrators as well. Force pushes and
branch deletion are disabled. Human approval count is zero for single-maintainer
development; this does not remove the PR or CI requirements.

Required checks:

- Android build + unit tests
- Backend typecheck + unit tests
- Backend chat integration + auth/persona smoke
- Model packager tests
- Inference telemetry Python contract (synthetic, no LLM)
- Persona synthetic evaluation contract (no LLM)

The opt-in Windows and security report checks are not required for every PR.
Keep required job names stable. When changing them, run the new check successfully
on a PR before updating the policy and retiring the previous required name. Keep
checks bound to the GitHub Actions app. Do not bypass failures to merge.

Inspect the live policy with:

```sh
gh api repos/pear1-pea/Persona/branches/master/protection
```

The 2026-11-10 acceptance PR #16 recorded a real failed required helper-test check
and GitHub BLOCKED merge status before the owned negative fixture was removed.
Recovery evidence must show all required checks successful and CLEAN merge status;
the PR's check history is the authoritative run record.

Validation uses an isolated acceptance branch and a temporary failing unittest in
the required CI helper suite. Only the acceptance branch contains the negative
fixture. Capture the failed required check and blocked PR merge state, remove the
fixture, then require successful checks and a clean merge state. Never merge the
negative fixture. The final acceptance change contains this guide only.

Merge eligibility is evaluated by GitHub after checks complete. Local unit tests,
manual Windows runs or synthetic status messages cannot replace required checks.

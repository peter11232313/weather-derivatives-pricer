---
name: new-feature
description: End-to-end workflow for implementing a feature from a spec in docs/specs/. Use when asked to build, add or implement a feature. Argument: spec number or path.
---
# New feature: $ARGUMENTS

1. **Spec check** — open the spec. If acceptance criteria are missing or vague,
   draft them, show me, and STOP until I approve.
2. **Plan** — delegate to the `planner` subagent. Show me the plan; wait for OK.
   If its first line is `PLAN: QUESTIONS (n)` (or the line is missing or malformed),
   show me the questions first and re-run the planner with my answers.
3. **Branch** — `git switch -c feat/<spec-number>-<slug>`.
4. **Tests first** — delegate to `test-writer`. Read its first line: `TESTS: RED (n)` → confirm
   the new tests fail yourself; `TESTS: BLOCKED` (or a missing or malformed line) → show me why
   and stop.
5. **Implement** — smallest change per plan step; run tests after each; commit
   each green step with a conventional commit message.
6. **Review (loop)** — delegate to `code-reviewer`, plus every domain reviewer the
   change touches: `quant-reviewer` for pricing maths, `met-reviewer` for weather/grid
   data (both if both apply). Then repeat:
   a. Read each reviewer's first line (`VERDICT: PASS` or `VERDICT: BLOCKED (n)`).
   b. If every reviewer returned `VERDICT: PASS`, the step is done.
   c. Otherwise fix every BLOCKER, run the tests, commit, and re-run **all** the
      reviewers (not only the ones that blocked) on the updated branch.
   After 3 rounds without all PASS, stop and show me the remaining blockers.
   A missing or malformed VERDICT line counts as BLOCKED.
7. **Wrap up** — tick the spec's criteria, update README/CHANGELOG, and give me a
   PR description: what, why, how tested, what's out of scope.
Never skip step 4 or 6. Never edit tests to make them pass without asking me.

New specs start from `docs/specs/_template.md`.

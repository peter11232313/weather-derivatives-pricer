---
name: new-feature
description: End-to-end workflow for implementing a feature from a spec in docs/specs/. Use when asked to build, add or implement a feature. Argument: spec number or path.
---
# New feature: $ARGUMENTS

1. **Spec check** — open the spec. If acceptance criteria are missing or vague,
   draft them, show me, and STOP until I approve.
2. **Plan** — delegate to the `planner` subagent. Show me the plan; wait for OK.
3. **Branch** — `git switch -c feat/<spec-number>-<slug>`.
4. **Tests first** — delegate to `test-writer`. Confirm the new tests fail.
5. **Implement** — smallest change per plan step; run tests after each; commit
   each green step with a conventional commit message.
6. **Review** — delegate to `code-reviewer`, plus the domain reviewer
   (`quant-reviewer` or `met-reviewer`). Fix every BLOCKER, then re-run review.
7. **Wrap up** — tick the spec's criteria, update README/CHANGELOG, and give me a
   PR description: what, why, how tested, what's out of scope.
Never skip step 4 or 6. Never edit tests to make them pass without asking me.

New specs start from `docs/specs/_template.md`.

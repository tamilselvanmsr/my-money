# Copilot Instructions — AutoLedger / mymoney

## 1. Think → Plan → Show → Code (always follow this order)

For **any non-trivial change** (UI redesign, new feature, layout change, multi-step fix):
1. **Think** — understand the full scope of what is being asked.
2. **Plan** — write out exactly what will change (files, functions, UI layout).
3. **Show** — present a visual diagram or plain-text summary of the result to the user.
4. **Code** — implement only after the user confirms ("looks good", "commit", "proceed", etc.).

Skip steps 2–3 only for trivial single-line fixes or when the user explicitly says "just do it".

## 2. Version management on every commit

- Before committing, **always ask the user what version to use** in the commit message. Never decide the version unilaterally.
- Version numbering follows `v2.X` (e.g. v2.9, v2.10, v2.11 …). Never jump to a new major (v3.x) — stay on v2.x indefinitely.
- Bump `versionCode` (integer) and `versionName` (string) in `app/build.gradle.kts` to match the agreed version before committing.
- Use the in-flight version as the commit subject prefix: `v2.X — <short description>`.
- **No duplicate version commits.** If the changes are small/incremental on the same version already pushed, amend the last commit and force-push (`git commit --amend --no-verify` + `git push --force origin master`) instead of creating a new commit.
- Never use `feat:` / `fix:` / `chore:` prefixes — plain `vX.Y — description` only.

## 3. Commit discipline

- One commit per version. Squash related changes before pushing.
- Commit message format: `v<X>.<Y> — <what changed in plain English>` (no imperative verbs, no emoji).
- Always pass `--no-verify` to skip pre-commit hooks that may block the commit.
- **For small/incremental changes**: amend the existing commit with `git commit --amend --no-verify --no-edit` (or update the message) and `git push --force origin master`. Do NOT create a new commit.

## 4. Code change safety — think, plan, confirm before altering existing code

- For **new feature requests**: proceed directly with implementation.
- For **changes to existing major code** (ViewModel, Room DAO/DB, SmsReceiver, filter logic, navigation, any >30-line composable): pause, show a **plan** listing every file and function that will change, and wait for explicit approval before writing any code.
- State all edge cases and potential regressions as part of the plan.
- Only after the user confirms ("looks good", "proceed", etc.) should you start editing files.
- Small isolated fixes (typos, label text, single-function tweaks) can be applied immediately without a plan step.

## 5. Build error fixes

- When the user pastes CI/build errors, fix them immediately, then `git commit --amend --no-verify --no-edit` + `git push --force origin master` — no new commit, no confirmation needed.

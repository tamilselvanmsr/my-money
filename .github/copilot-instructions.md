# Copilot Instructions — AutoLedger / mymoney

## 1. Version management on every commit

- Before committing, always bump `versionCode` and `versionName` in `app/build.gradle.kts` to reflect the version being committed.
- Use the in-flight version as the commit subject prefix: `v1.79 — <short description>`.
- **No duplicate version commits.** If the changes are small/incremental on the same version already pushed, amend the last commit and force-push (`git commit --amend --no-verify` + `git push --force origin master`) instead of creating a new commit.
- Never use `feat:` / `fix:` / `chore:` prefixes — plain `vX.Y — description` only.

## 2. Commit discipline

- One commit per version. Squash related changes before pushing.
- Commit message format: `v<X>.<Y> — <what changed in plain English>` (no imperative verbs, no emoji).
- Always pass `--no-verify` to skip pre-commit hooks that may block the commit.

## 3. Code change safety — think, plan, confirm before altering existing code

- For **new feature requests**: proceed directly with implementation.
- For **changes to existing major code** (ViewModel, Room DAO/DB, SmsReceiver, filter logic, navigation, any >30-line composable): pause, show a **plan** listing every file and function that will change, and wait for explicit approval before writing any code.
- State all edge cases and potential regressions as part of the plan.
- Only after the user confirms ("looks good", "proceed", etc.) should you start editing files.
- Small isolated fixes (typos, label text, single-function tweaks) can be applied immediately without a plan step.

## 4. Build error fixes

- When the user pastes CI/build errors, fix them immediately, then `git commit --amend --no-verify --no-edit` + `git push --force origin master` — no new commit, no confirmation needed.

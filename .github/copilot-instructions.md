# Copilot Instructions — AutoLedger / mymoney

## 1. Think → Plan → Show → Code (always follow this order)

For **every non-trivial change** (UI redesign, new feature, layout change, multi-step fix):
1. **Think** — understand the full scope of what is being asked.
2. **Plan** — write out exactly what will change (files, functions, UI layout).
3. **Show** — present a plain-text summary of the result. When the change has visible UI or layout impact, also present a visual diagram.
4. **Code** — implement only after the user confirms ("looks good", "commit", "proceed", etc.).

Skip steps 2–3 only for trivial single-line fixes or when the user explicitly says "just do it". Do not treat a new feature as an automatic exception to this process.

## 2. Version management on every commit

- Before committing, **always ask the user what version to use** in the commit message. Never decide the version unilaterally.
- Before proposing a new version, inspect the recent commit history and continue its `v2.X` sequence and message style.
- Version numbering follows `v2.X` (e.g. v2.9, v2.10, v2.11 …). Never jump to a new major (v3.x) — stay on v2.x indefinitely.
- Bump `versionCode` (integer) and `versionName` (string) in `app/build.gradle.kts` to match the agreed version before committing.
- Use the in-flight version as the commit subject prefix: `v2.X — <short description>`.
- **No duplicate version commits.** Small or incremental changes must not receive a new version. Amend the last commit and force-push (`git commit --amend --no-verify` + `git push --force origin master`) instead.
- Never use `feat:` / `fix:` / `chore:` prefixes — plain `vX.Y — description` only.

## 3. Commit discipline

- One commit per version. Squash related changes before pushing.
- Commit message format: `v<X>.<Y> — <what changed in plain English>` (no imperative verbs, no emoji).
- Always pass `--no-verify` to skip pre-commit hooks that may block the commit.
- **For small/incremental changes**: amend the existing commit with `git commit --amend --no-verify --no-edit` (or update the message) and `git push --force origin master`. Do NOT create a new commit.

## 4. Code change safety — think, plan, show, confirm before altering existing code

- For every non-trivial feature or modification, pause, show a **plan** listing every file and function that will change, and wait for explicit approval before writing any code.
- For visual changes, include a visual diagram that shows the proposed layout or interaction flow before requesting approval.
- State all edge cases and potential regressions as part of the plan.
- Only after the user confirms ("looks good", "proceed", etc.) should you start editing files.
- Small isolated fixes (typos, label text, single-function tweaks) can be applied immediately without a plan step.

## 5. Build error fixes

- When the user pastes CI/build errors, fix them immediately, then amend and force-push: `git commit --amend --no-verify --no-edit` followed by `git push --force origin master`.
- Never create a new version or a new commit solely for a build-error fix.
- Always include `--no-verify` in commit or amend commands so local verification hooks do not run.
- Never run local Gradle compilation, build, assemble, lint, or test commands. Do not invoke `./gradlew` locally; use editor diagnostics and whitespace checks instead.

## 6. Kotlin / Compose code formatting rules

Apply these rules to every file touched in this workspace.

### Indentation and spacing
- **4 spaces** per indent level. No tabs anywhere.
- Opening braces `{` always on the same line as the statement (K&R style).
- Closing braces `}` on their own line, aligned with the opening statement.
- One space before `{`, one space after `:` in type annotations, one space around binary operators (`+`, `-`, `=`, `->`, etc.).
- One space after every comma in argument lists; no space before commas.
- No trailing whitespace on any line. Blank lines must contain zero characters.
- No whitespace-only blank lines (lines that contain only spaces or tabs).

### Line width
- **Soft limit: 160 characters.** Lines should stay at or below this.
- **Hard limit: 200 characters.** No line may exceed this.
- Break long Compose modifier chains by placing each `.modifier()` call on its own line, indented 4 spaces past the base expression.
- Break long lambda argument lists so each named argument starts on its own line at +8 from the function call.

### Blank lines
- One blank line between adjacent top-level function declarations.
- One blank line between logically distinct blocks inside a composable (e.g., state declarations vs UI tree).
- No more than **two consecutive blank lines** anywhere in the file.

### Formatting-only commits
- A formatting-only change must not alter any logic, argument order, import set, or composable parameter name.
- After formatting, run editor diagnostics and a `git diff --check` (whitespace check) before committing.
- Do not mix formatting changes with behavior or UI changes in the same commit.

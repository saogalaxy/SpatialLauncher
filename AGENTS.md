# Agent instructions — Spatial Launcher

Read this before changing app code. The owner was burned by shipped regressions;
every change must prove it adds no bugs.

## Before editing

1. **Read first.** Use `Read` on the exact region you will touch (never edit
   blind). Derive `oldString` from current file content; keep the replacement
   boundary minimal.
2. **Check callers + lifecycle.** For Quest code, trace who calls the function
   and when (tap-time vs resume vs grant-result vs background). A fix that runs
   at the wrong lifecycle moment is a new bug (e.g. FGS promotion must happen
   at tap time, not after the share-sheet grant).
3. **Know the annotations.** `@UnstableApi` (media3 marker) *propagates* the
   opt-in requirement to callers; `@OptIn(markerClass = UnstableApi.class)`
   *contains* it. `@SuppressLint` never propagates — use only with a comment
   explaining why containment is impossible.

## After editing

1. **Re-read the edited region** and confirm preservation constraints
   (no dropped lines, no duplicated symbols).
2. **Build + lint, every time:**
   `.\gradlew.bat :app:assembleRelease :app:lintDebug --no-daemon`
   Both must be green. `lint-baseline.xml` locks all accepted warnings — any
   NEW finding fails the build. Clean it up; do not regenerate the baseline
   to silence a finding you introduced (verify with the overlap check below).
3. **Overlap check (mandatory for `app/src` changes):** intersect
   `git diff <base> -U0 -- app/src/main` hunks with
   `app/build/intermediates/lint_intermediate_text_report/debug/lint-results-debug.txt`.
   Result must be **zero findings inside changed hunks**.
4. **Behavior-neutral proof:** annotation-only, dead-code, XML-attr, and
   comment changes must not alter runtime behavior — say so explicitly in the
   commit message (`chore:`) vs behavior changes (`fix:`/`feat:`).
5. **On-device verification for Quest flows:** install same-signature replace
   (`npx metavr app install ... --replace --grant-permissions`), reproduce the
   user path, pull `logcat` filtered by the feature TAG. Never declare a flow
   fixed without log or eyes-on evidence. Never install the debug APK over a
   release install (or vice versa) without warning about the data wipe —
   signatures do not cross `--replace`.
6. **PowerShell quoting:** inline `bash` commands mangle `$variables` — put
   `$`-heavy checks in a file under `C:\Users\usbma\AppData\Local\Temp\opencode\`
   and run with `-File`. Validate edited `.ps1` with the language parser
   (`SYNTAX OK`) before committing.

## Standing facts (do not regress, do not "fix")

- `minSdk 29`: `Build.VERSION` branches for Q/N/O and below are dead code.
- `POST_NOTIFICATIONS` is intentionally undeclared (Security.2 trim); the
  in-app banner is the status surface, shade notifies are best-effort with a
  runtime grant guard + documented `@SuppressLint`.
- Release signing falls back to debug signing without `keystore.properties` —
  never upload such an APK to the Meta Store (see `docs/RELEASE_SIGNING.md`).
- SenseVoice prompts/downloads only on first Listen-icon tap, never at startup.
- `CHANGELOG.md` gets an entry with every notable fix/feature.

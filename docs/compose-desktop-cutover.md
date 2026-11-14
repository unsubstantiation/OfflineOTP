# Compose desktop cutover gates

**Status: blocked; Swing remains the shipping client.**

Sandbox compilation uses stubs and is not real Compose runtime validation. Android device
validation does not validate the desktop client. No validation result may be carried forward
from master or an earlier commit without identifying what changed and what was retested.

## Automated evidence

Once the new workflow is available on the default branch, run Actions → **Compose desktop
validation** → Run workflow → the migration branch. GitHub may not expose a newly added
branch-only workflow in the Actions UI; publishing it to the default branch requires a separately
approved PR. Until then, maintainers can run the same commands locally on each target OS with
JDK 17 and Android SDK platform/build tools 36 installed:

```sh
python scripts/check_build_inputs.py
bash ./gradlew --no-daemon --stacktrace :core:test :ui-model:desktopTest :desktop:test :desktop-compose:test :desktop-compose:installDist
```

Record the exact `git rev-parse HEAD` and retain the logs/JUnit XML with the manual evidence.
The workflow compiles the actual Compose dependency graph, runs JVM/headless tests and assembles
`:desktop-compose:installDist` on Linux, Windows and macOS. It records the exact commit and
uploads logs/JUnit results. It does **not** launch the UI, test native crypto/devices, build signed
installers or claim release-package readiness. A successful installDist is only a distribution
assembly check. Inspect skips and actual test counts, not just the green job icon.

Also run **Android build** manually on the same branch and inspect both compose-validation
matrix jobs (Fdroid API 28 and Play API 36). The result checker must pass; a successful master
push build without these dispatch-only jobs is not device validation.

Dispatch from Arena was denied by GitHub with HTTP 403. Until workflow permissions are fixed,
a repository maintainer must dispatch from GitHub. Do not open a PR just to trigger checks.

## Manual desktop matrix — use disposable vaults only

Record commit, OS/version, architecture, Java version, display scale, tester, date and outcome.
Retain sanitized evidence; never upload vault files, passwords, token seeds, PINs or screenshots
containing revealed secrets. Run on each supported desktop OS and architecture before release.

- Launch from the assembled distribution, not the IDE. Verify native libraries and assets load.
- Create/unlock/lock/reopen; wrong-password and corrupt-file errors; password change/recovery.
- Idle lock while a dialog, picker, export/import or hardware operation is active; suspend/resume.
- Search/filter/order, each entry type, conceal/reveal and stale-dialog rejection.
- Clipboard copy, expiry and ownership; HOTP persistence and counter confirmation.
- Real file pickers, overwrite refusal, cancellation, permission failures and portable storage.
- Backup/import/export round trips using real native crypto, including multiple OpenPGP recipients.
- Email metadata/password changes, staged identity removal/undo and export/reimport preservation.
- Physical PC/SC/FIDO/OATH unlock/enrollment/removal/copy, timeout, cancellation and unplug cases.
- Keyboard focus/traversal, screen reader, scaling, scrolling, contrast and masked-secret behavior.
- Close/relaunch and shutdown during work; no stale secret dialogs after session termination.

Android also needs real visual/accessibility/lifecycle checks beyond the emulator assertions.
Hardware and camera tests require appropriate physical devices; emulator success is insufficient.

## Release/cutover decision

1. Complete or explicitly accept each remaining feature gap in `compose-unified-ui-plan.md`
   (including camera, OpenPGP hardware/agent workflows and remaining email/persistence work).
2. Pass automated checks and record the manual matrix for the candidate commit.
3. Implement and validate native-library staging, runtime dependencies, icons and launch arguments
   in Compose release packaging on all platforms. Existing Swing packaging is not evidence for it.
4. Prepare a launcher switch with a documented fallback/rollback and vault compatibility checks.
5. Obtain explicit approval for cutover. Ask separately before opening a PR.
6. Switch the shipping entry point in a gated increment. Retire Swing only after the new release
   path and agreed fallback have been validated; do not delete the shared desktop engine.

## Evidence ledger

| Gate | Commit / run URL | Outcome |
| --- | --- | --- |
| Android emulator matrix | Not available | Pending |
| Desktop real Gradle matrix | Not available | Pending |
| Desktop manual/runtime matrix | Not available | Pending |
| Physical hardware / native crypto | Not available | Pending |
| Release packaging and rollback | Not available | Pending |

This checklist is preparation, not a validation attestation.

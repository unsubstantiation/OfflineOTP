# Compose desktop cutover gates

**Status: Compose cutover and Swing retirement are complete (PR #125, merged at
`3e8fb2700a5622f6c7e4062283190cf82ac7165d`).**

On 2026-11-07 the user reported: “I confirmed build is good and working on device.”
This records a successful user-reported build/device check. The platform, device/OS, tested
commit or artifact, and exercised workflows were not supplied. The merge commit above identifies
the code transition, **not** the build that was tested. Do not infer Android versus desktop,
all-platform coverage, hardware coverage or release-package readiness from this report.

Validation remains incomplete; this is not a validated release. This means the full release
matrix is not recorded, not that the user's successful check is being discounted.

## Cutover and rollback commands

- Default: `./gradlew :desktop-compose:run` (also `:desktop:run`).
- Release app image: `./gradlew :desktop-compose:packageDesktop`.
- No in-tree Swing fallback remains. Rollback requires a previously validated release and a backup.
- Back up first. Close one client before opening the other; never share an active vault between
  two processes. Use the same `OFFLINEOTP_HOME` deliberately for rollback, or a disposable copy
  for testing. No on-disk format migration is part of this switch.
- The shared desktop dependency now contains engine and AWT artwork code, not Swing UI.
- Desktop release workflows target Compose. A successful build/device check is user-reported;
  detailed platform/package evidence remains unrecorded in the ledger below.


Sandbox compilation uses stubs and is not real Compose runtime validation. Android device
validation does not validate the desktop client. No validation result may be carried forward
from master or an earlier commit without identifying what changed and what was retested.

## Automated evidence

The validation workflow is now present on master following PR #125. Run Actions →
**Compose desktop validation** → Run workflow → the candidate branch/ref. Maintainers can also
run the same commands locally on each target OS with JDK 17 and Android SDK platform/build tools
36 installed:

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

A previous dispatch attempt from Arena was denied by GitHub with HTTP 403; permissions have
not been rechecked for this documentation update. If that restriction persists,
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

## Remaining release checks (not another cutover)

1. Complete or explicitly accept each feature gap listed in the current-status section of
   `compose-unified-ui-plan.md`. Not every enhancement must block a scoped release.
2. Record the tested artifact/commit and platform for the user's successful check, then run
   any untested workflows in the manual matrix for the candidate release.
3. Validate the implemented native-library staging, runtime dependencies, icons and launch
   arguments on supported platforms. Prior Swing packaging does not validate Compose packages.
4. Check backup/restore and rollback using a prior validated release and disposable vault copies.
5. Ask separately before opening a new PR; cutover approval has already been given and acted on.

## Evidence ledger

| Gate | Commit / run URL | Outcome |
| --- | --- | --- |
| User-reported build/device check (2026-11-07) | Tested commit/artifact and platform not supplied | Reported successful; detailed scope unrecorded |
| Android emulator matrix | Not recorded here | Awaiting scoped evidence |
| Desktop real Gradle matrix | Not recorded here | Awaiting scoped evidence |
| Desktop manual/runtime matrix | Not recorded here | Awaiting scoped evidence |
| Physical hardware / native crypto | Not recorded here | Awaiting scoped evidence |
| Release packaging and rollback | Not recorded here | Awaiting scoped evidence |

This checklist is preparation, not a validation attestation for the complete release matrix.

### Information to attach to the successful check

- Platform (Android or packaged desktop), OS/version and device/architecture
- Tested commit, version/artifact identifier, or build/run URL
- How the app was launched/installed and which workflows were exercised
- Any failures, skipped hardware checks or known limitations

Do not attach vaults, credentials, PINs, recovery material or screenshots with revealed secrets.

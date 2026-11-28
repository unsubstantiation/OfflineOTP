# Compose desktop cutover

Compose replaced the Swing desktop UI; no in-tree Swing renderer or fallback tasks
remain. PR #125 (`3e8fb2700a5622f6c7e4062283190cf82ac7165d`) records that transition,
not the exact artifact tested later. Engine and AWT services remain in `:desktop`.

## Verification

The user initially reported a successful unspecified device/build check and later
confirmed all non-iOS builds/workflows and platform checks verified. That supersedes
earlier blanket claims that desktop runtime validation had never occurred. Detailed
per-scenario artifact logs are not recorded here. iOS is separately build-blocked.
See [current status](shared-ui-migration-status.md).

The desktop validation workflow runs real Gradle tests and assembles distributions;
it does not launch every UI route or prove hardware behavior. Local JVM/stub tests
are not substitutes for that workflow or the user's platform checks.

## Commands and rollback

```sh
./gradlew --no-daemon :desktop-compose:run
./gradlew --no-daemon :desktop-compose:packageDesktop
```

`:desktop:run` and package aliases forward to Compose. Rollback requires a prior
validated release and a tested vault backup. Never run two processes against one
active vault; use disposable copies when checking compatibility.

Desktop data defaults inside the program folder. `OFFLINEOTP_HOME` or
`-Dofflineotp.home` selects another writable directory. Package changes do not
justify dropping vault formats, unknown fields or recovery candidates.

## Candidate checks

For subsequent changes, record the artifact, OS/architecture and scope tested:
packaged launch/native libraries, recovery and interrupted writes, clipboard expiry,
lock/suspend/cancellation, import/export, hardware capabilities and accessibility.
Use the [device checklist](shared-ui-device-checklist.md),
[native validation guide](shared-ui-native-validation.md) and
[packaging guide](../scripts/packaging/README.md). Historical batch ledgers remain
in Git history rather than being presented as current unperformed work.

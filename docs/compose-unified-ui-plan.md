# Shared Compose UI plan

## Current position

Shared Compose owns app-rendered vault screens, forms and dialogs. Swing and SwiftUI
production views are removed. Portable rendering lives in `:ui-foundation`, contracts
in `:ui-contract`, and JVM adapters in `:ui-model`/`:ui` `jvmHostMain` source sets.
See [ownership](shared-ui-ownership.md) for the current dependency boundary.

The user reports verification of all non-iOS builds/platforms. The iOS build failed;
subsequent Kotlin service replacements and workflow fixes await native execution.
UI sharing is not proof that all iOS engine/orchestration logic is shared: significant
Swift dependencies remain in production.

## Completed source consolidation

- Shared card/code/action and drag-list rendering, with stable-identity frame delivery.
- Shared unlock/setup, entry and credential forms, backup/restore/import/export dialogs,
  settings surfaces, About and app-owned scanner controls.
- Portable artwork/vector presentation, common theme/About sources and defensive masking.
- JVM engine adapters separated from portable common source sets.
- Kotlin replacements for iOS code formatting, clipboard, QR-image reading, native
  palette bindings and About-link lookup; their Swift implementations are deleted.

## Remaining work

1. Finish the [iOS Kotlin engine/host cutover](ios-kotlin-cutover.md). Retain only a
   thin Swift bridge for official YubiKit APIs; do not preserve an alternate app engine.
2. Complete a successful native iOS framework/app build, then validate exports,
   lifecycle, recovery, privacy, accessibility and hardware on equipped targets.
3. Retire hidden Android compatibility state/controllers only after their remaining
   callers and restoration behavior have replacements. Do not delete live services.
4. Keep platform capability differences explicit. NFC/USB-C, biometric availability,
   provider signing and file permissions cannot be inferred from a shared dialog.

## Acceptance rules

- One portable implementation per supported app screen, with no selectable legacy UI fallback.
- Native hosts authorize operations; composable enablement alone is not authorization.
- Cancellation, lock, background and replacement reject stale native results.
- Secret drafts and detached snapshots have explicit owners and disposal paths.
- Portable vault-copy/password recovery and existing wire formats remain compatible.
- Tests identify their evidence level: source, stub/JVM, real target build or device run.

## Validation entry points

```sh
python3 scripts/check_shared_boundaries.py
python3 -m unittest discover -s scripts/tests
bash scripts/kotlin-sandbox/compile-ui.sh
./gradlew --no-daemon :ui-model:desktopTest :desktop-compose:test
```

Use the [native handoff](shared-ui-native-validation.md) and
[device checklist](shared-ui-device-checklist.md) for runtime validation. Source/stub
checks do not render the UI. Historical batch-by-batch implementation notes are
available in Git history; they are not the current task list.

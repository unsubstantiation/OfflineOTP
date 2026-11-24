# Shared UI completion status

The shared UI migration is **not complete**. Shared Compose components and Android overview routes
must not be confused with a fully shared application shell. This checklist records known remaining
work after the Settings and two onboarding-step migrations; it is not a device-validation report.

## Known remaining app-owned native presentation

- Android `AuthenticateActivity` now has a common Compose layout, with the original Android input
  deliberately retained as an autofill/accessibility capability adapter. Its real integration still
  needs device validation; authentication tasks and platform security enforcement stay native.
- Android `IntroScreenActivity`: wizard shell/navigation. Encryption, credential entry/confirmation, platform-backup
  information and completion (create/restore, progress/error/retry/success) step bodies now use
  common Compose; native hidden validation/compatibility engines remain behind the shared bodies.
- Android `AboutActivity` / flavor About fragments: app details, special-feature enablement and flavor
  actions. Generated AboutLibraries version/license information must be retained when replacing tabs.
- Advanced Android Settings: authentication/encryption changes, biometric enrollment, backup password
  and login-password mode (including legacy-password removal), provider application and signing-key
  selection, special-feature toggle and KeyStore reset confirmation/dispatch. Some confirmations are
  already shared, but the full native preference destination still exists.
- Remaining app-owned notice/toast/progress surfaces need review and an explicit ownership decision.
- Cross-platform parity audit: distinguish native capability adapters from duplicated layout, verify
  Desktop and iOS entry points use the shared features they support, and check feature availability.

## Platform surfaces intentionally retained

OS document pickers, permission sheets, biometric prompts and provider-owned security-key/PGP UI are
capability adapters, not candidates for imitation in Compose. Their app-owned launch, feedback and
configuration UI should be shared where practical; security/lifecycle enforcement remains native.

## Completion gates

- No remaining app-owned native screen/preference tree used as the visible feature implementation.
- Every existing feature mapped to a shared route or documented platform-only capability.
- No loss of restore, export, credential, provider, licensing or first-run behavior.
- Lifecycle/session/cancellation and sensitive-input retention checks for newly migrated flows.
- Real Android/desktop rendering and native iOS build/runtime checks in addition to sandbox compilation.

The current sandbox cannot establish the last gate. Do not label the migration complete merely
because structural and JVM stub checks pass.

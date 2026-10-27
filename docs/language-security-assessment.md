# Language conversion assessment: security and Android compatibility

> **Historical assessment.** This reviews the 2026-10-14 baseline
> (`be039203c2baa104a559e3a62beff96b70819881`). The app main sources are now fully Kotlin; see
> the root [README](../README.md) for current build and test commands. GitHub Actions workflows
> have since been removed. Findings, risks and recommendations below describe that baseline, not
> necessarily current behavior. The linked source files have since changed; see the
> [vault security record](vault-security-hardening.md) for implemented hardening and limits.

Reviewed: 2026-10-14. Baseline: `be039203c2baa104a559e3a62beff96b70819881`.

## Recommendation

**Keep the application native Android. Introduce Kotlin incrementally alongside Java; do not undertake a full rewrite or move the current cryptography into native code.** Prioritize explicit security fixes and regression tests before converting security-critical implementations.

Java is already a memory-safe managed language in this project. Kotlin's useful gains are nullability, validated domain types, exhaustive state handling, and better lifecycle/concurrency expression—not a stronger Android sandbox. Neither Kotlin nor Rust automatically prevents another app from invoking an exported component, receiving an implicit intent, or supplying malicious document data.

Retaining and hardening Java was a sound alternative if the team could not maintain Kotlin
confidently. Maintenance capacity matters: introducing another language without ownership can
reduce security.

This assessment interprets “language conversions” as programming-language migrations, not translation of UI strings. It makes no application behavior changes.

## Scope and confidence

Reviewed build configuration, manifest, source layout, OTP/entry parsing, encryption and key management, database persistence, authentication/background task lifecycle, backup/restore and document handling, exported receivers/activities, token sharing, and existing tests.

This is **static source analysis, not a penetration test or a security certification**. Findings below distinguish observed behavior from implications that require device testing. Dependency versions, transitive manifests, packaged native libraries, actual hardware-backed key behavior, and Android/OEM behavior have not been verified in a built APK.

`./gradlew --version` fails because the environment has neither `JAVA_HOME` nor a `java` executable. Android SDK/ADB tools were not found on PATH. No Gradle build, lint task, emulator test, or instrumentation test was run. Dependency resolution also requires hosts beyond this environment's permitted outbound host list. Build and runtime compatibility therefore remain unverified.

## 1. Repository baseline

| Area | Observed state | Implication |
| --- | --- | --- |
| Application language | The 2026-10-14 baseline had 22 main-source Java files. Subsequent batches converted all app main sources to Kotlin; host and instrumentation tests are under `app/src/test/kotlin` and `app/src/androidTest/kotlin`. | No in-repository C/C++ core needs replacing; see the [conversion record](kotlin-extracted-helpers.md) for migration history. |
| Native/other runtimes | Kotlin (JVM) sources are checked in under `app/src/main/kotlin`; no Rust, C, or C++ implementation | Rust would introduce JNI, native packaging and a second toolchain rather than remove existing native exposure |
| Build | Three Groovy Gradle files; Gradle 8.13 wrapper; Android Gradle Plugin 8.13.2; Kotlin plugin 2.4.21 pinned with `jvmTarget` 1.8 | Kotlin is added; keep plugin/AGP/Gradle compatibility verified, and note that no Gradle build has been executed in this environment |
| Android contract | `minSdkVersion 28`, `targetSdkVersion 35`, `compileSdk 36`; Java source/target 8 and core-library desugaring | Declared support begins at Android 9, not the older Android versions supported by historical upstream releases |
| UI | Android activities, XML layouts, preferences, RecyclerView; data binding enabled | Kotlin works with the existing UI; no need to combine language migration with Compose |
| Storage/crypto | Encrypted JSON in private files; JCA AES-GCM/PBKDF2/HMAC; RSA key wrapping through AndroidKeyStore | Preserve platform providers and existing file formats during language migration |
| Integration | ZXing, OpenPGP service, Storage Access Framework, WorkManager, process lifecycle callbacks | Security depends heavily on Android trust boundaries and lifecycle policy |
| Tests | RFC TOTP/HOTP, entry parsing/serialization, database round-trip and AES-GCM vectors in `ApplicationTest`; token formatting in `ToolsTest` | Baseline vectors were useful, but adversarial-input, process-death and migration coverage were thin; subsequent tests expanded coverage without replacing device testing |
| Build assurance | At baseline, lint had `abortOnError false` and no GitHub Actions workflow was checked in | Lint now fails on errors; build and device checks must be run separately |

Sources: [app build](../app/build.gradle), [root build](../build.gradle), [wrapper](../gradle/wrapper/gradle-wrapper.properties), [manifest](../app/src/main/AndroidManifest.xml), [tests](../app/src/androidTest/kotlin/org/shadowice/flocke/andotp/ApplicationTest.kt).

Java bytecode compatibility is not the Gradle runtime requirement: AGP 8.x needs a supported JDK such as JDK 17. Adding Kotlin does not inherently raise `minSdk`; chosen libraries and Android API calls can. Modern Kotlin language syntax also does not make arbitrary newer Java/Android APIs available on API 28—check desugaring support and API guards.

## 2. Threat model

Protect OTP seeds, passwords/PINs, encryption keys, live OTPs, account metadata, and recoverability of the vault.

### Network-origin threats

The checked-in application manifest does **not** declare `INTERNET`, and the reviewed app source does not implement a network client or embedded WebView. Keep this offline design. At the baseline, `Entry` used `URL` to parse text, not to fetch it; the current parser uses `java.net.URI`.

This is not proof that the release APK cannot use the network: library manifests must be merged and inspected for both flavors. Also, a browser can deliver an `otpauth` link, a cloud document provider can supply hostile content, Android backup can transfer data, and the OpenPGP or token-sharing recipient can have network access independently. No app-level network permission is needed for those trust-boundary crossings.

Recommended invariant: fail CI if the **merged release manifest** gains `INTERNET`, unwanted exported components, or unexpected permissions. Exercise the packaged app under network observation as a separate verification step. A `usesCleartextTraffic="false"` policy is defense in depth if networking is ever introduced, not a substitute for remaining offline.

### Malicious apps on the same device

Assume an ordinary third-party app can send explicit intents/broadcasts to exported components, register as a token-sharing handler, and act as a selected document provider. Treat external inputs as hostile even if the user chose a file. Assume Android's normal UID sandbox and signature protections are functioning.

Separately assess user-enabled accessibility services, keyboards, autofill providers, overlays, and screen capture. There are already screenshot restrictions and optional accessibility/autofill controls; they are useful but not complete isolation from highly privileged or user-authorized services. Do not claim that switching languages protects against root, a compromised OS, or a malicious accessibility service. Blanket accessibility blocking also has usability costs.

## 3. Security work with greater benefit than a language conversion

The observations and proposed actions below are from the baseline. Priorities are not CVSS scores or claims of demonstrated exploitation.

### A. Restrict destructive external IPC — high priority

**Observed:** `PanicResponderActivity` is exported without a manifest permission. Its `onCreate` accepts the public panic action and deletes the database/keys and/or settings according to the user's configured panic response. The response defaults to an empty set, so deletion is conditional rather than enabled for everyone.

**Risk:** Once destructive panic responses are configured, an unrelated app can attempt to invoke the same action. Action-string equality is not caller authentication. This is an availability/integrity issue, not evidence that seeds can be read.

**Action:** Decide whether arbitrary apps are intentionally trusted to trigger panic. Otherwise remove external access or require a user-authorized integration/capability. A signature permission works for same-signer applications but would break independently signed PanicKit integrations; do not apply it blindly. Test destructive behavior using disposable test data and an independently signed attacker app.

Evidence: [manifest](../app/src/main/AndroidManifest.xml); [PanicResponderActivity](../app/src/main/kotlin/org/shadowice/flocke/andotp/Activities/PanicResponderActivity.kt) (Kotlin since the nineteenth batch; current implementation; panic authorization was subsequently hardened); [Settings.getPanicResponse](../app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/Settings.kt) (Kotlin since the sixteenth batch; the stored keys and the default are unchanged).

### B. Validate and confirm externally supplied OTP entries — high priority

**Observed:** `MainActivity.checkIntent` accepts `ACTION_VIEW`, parses an entry, computes its OTP and adds it without an entry-preview/confirmation step in that path. Normal authentication flow exists; this observation does **not** establish an authentication bypass. `Entry(String)` rewrites `otpauth` to `http`, accepts the resulting HTTP scheme, and parses numeric parameters without enforcing domain ranges there. Manifest filters do not constrain explicitly addressed intents. (Since then, `OtpUriParser`/`EntryJson` enforce input bounds and `ACTION_VIEW` imports show a confirmation. QR scan/file imports follow a different path; see the [current security record](vault-security-hardening.md).)

**Risk:** Unwanted account insertion and malformed values reaching token generation. For example, a zero period reaches time-step division; excessive digits/labels/tags can cause failures or resource consumption. QR/file and JSON paths need the same validation, not separate UI-only checks.

**Action:** Require the actual expected scheme and supported OTP type; reject missing/invalid Base32 secrets, invalid periods, digit counts, counters and algorithms; bound strings and collections; define behavior for duplicate parameters and Unicode/locale cases. Preserve supported Steam/mOTP behavior deliberately. Present a confirmation screen before committing external entries.

Evidence: [MainActivity, `checkIntent` and `addQRCode`](../app/src/main/kotlin/org/shadowice/flocke/andotp/Activities/MainActivity.kt) (Kotlin since the twenty-first batch; intent handling has since changed); [Entry](../core/src/main/kotlin/org/shadowice/flocke/andotp/Database/Entry.kt) (converted to Kotlin; now in the shared `:core` module; `Entry(String)` and `Entry(JSONObject)` are now adapters over [OtpUriParser](../core/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/OtpUriParser.kt) and [EntryJson](../core/src/main/kotlin/org/shadowice/flocke/andotp/Database/EntryJson.kt)); [TokenCalculator](../core/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/TokenCalculator.kt) (converted to Kotlin; now in the shared `:core` module; current input bounds are enforced by `InputPolicy`).

### C. Bound document, image and password-derivation work — high priority

**Observed:** `StorageAccessHelper.loadFile` reads an entire provider stream into memory without a size cap. `ScanQRCodeFromFile` decodes the entire image, dereferences the bitmap without checking decode success, and allocates a pixel array from width × height. `EncryptedRestoreTask` takes a PBKDF2 iteration count from the backup header without a policy maximum before deriving a key.

**Risk:** A chosen malicious document can cause excessive allocation, decode crashes, or prolonged CPU consumption. Catching `Exception` is not a resource-limit strategy and does not handle an out-of-memory failure reliably.

**Action:** Enforce byte limits during reading, regardless of provider-reported size; inspect image dimensions before decoding and subsample/reject large images; check null results and multiplication overflow; validate minimum backup length and bounded KDF parameters before work begins. Enforce entry/tag/secret limits after decrypting as well. Allow valid historical backup parameters under an explicit compatibility policy rather than simply requiring the current writer's exact range.

Evidence: [StorageAccessHelper.loadFile](../app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/StorageAccessHelper.kt) (Kotlin since the twelfth conversion batch; now bounded by `BoundedInputReader`); [ScanQRCodeFromFile.scanQRImage](../app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/ScanQRCodeFromFile.kt) (Kotlin since the thirteenth batch; bounds-checked before and after decoding); [EncryptedRestoreTask.doInBackground](../app/src/main/kotlin/org/shadowice/flocke/andotp/Tasks/EncryptedRestoreTask.kt) (Kotlin since the sixth batch; the header is parsed by `EncryptedBackupHeader`).

### D. Separate vault failures from a valid empty vault — high priority

**Observed:** `DatabaseHelper.loadDatabase` returns an empty list after missing-key/read/decryption failures. `stringToEntries` catches errors around the whole loop and can return entries parsed before a later failure. `tryEncryptionChange` consumes the returned list. `EncryptedBackupWorker` can pass the returned list to a backup task and report success. Database writes overwrite the file directly.

**Risk:** Corruption or key failure can be mistaken for a valid empty database; malformed imports can be treated as partial success. Subsequent save, re-encryption or backup can replace useful data with empty/partial output. With a fixed backup filename this can overwrite the prior backup; timestamped names reduce that specific overwrite risk but not false success.

**Action:** Return explicit `Missing`, `Success`, `WrongKey/AuthenticationFailure`, `Corrupt`, and `IoFailure` outcomes. Treat missing data as first-run only under an explicit initialization policy. Never mutate/backup a failed load. Parse imports transactionally and report rejected entries explicitly. Use atomic private-file replacement and consistent synchronization across save, migration, backup and wipe; verify rollback before discarding the old data/key. SAF providers need a separate safe-write policy because rename/atomicity support varies.

Evidence: [DatabaseHelper, readVault and changeVaultEncryption](../app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/DatabaseHelper.kt) (converted to Kotlin in the eleventh batch; the typed outcomes and the transaction are the ones described in the vault-hardening record); [FileHelper.writeBytesToFile](../app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/FileHelper.kt) (Kotlin since the twenty-second batch); [EncryptionHelper.tryEncryptionChange](../app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/EncryptionHelper.kt) (Kotlin since the fifteenth batch; the staged commit and its guard order are unchanged); [EncryptedBackupWorker, the vault read under the file lock](../app/src/main/kotlin/org/shadowice/flocke/andotp/Tasks/EncryptedBackupWorker.kt).

### E. Make locked state a real key-lifetime boundary — high priority

**Observed:** `MainActivity.onSaveInstanceState` can put raw key bytes into `encKey` for recreation, and `onCreate` restores them. Keys also cross internal activity extras. Background relock sets `requireAuthentication` but does not itself clear the adapter's key/entries. `KeyStoreHelper` creates an RSA wrapping key without a `setUserAuthenticationRequired` policy; the AES vault key is unwrapped into application memory.

**Risk:** Keys live in more places and for longer than necessary. Device-credential confirmation is currently an application-level gate, not cryptographic proof required by the wrapping key. This is not proof that an ordinary app can read private Bundles or intercept explicitly targeted internal activity extras.

**Action:** Centralize vault session state; do not put raw secrets in saved state or `SavedStateHandle`; reauthenticate after process death. Clear session references and token UI on lock, screen-off and the selected background policy, with short-lived operation-scoped exceptions where necessary. Minimize immutable password strings and copies; best-effort wipe owned byte/char arrays, without promising complete managed-heap erasure.

For a stronger interactive mode, evaluate a non-exportable AndroidKeyStore AES-GCM key with an appropriate device-credential/biometric authorization policy. Keep legacy decryption and make migration recoverable. Verify hardware security level on actual devices; hardware backing is not universal. **Auth-bound keys conflict with unattended encrypted backups:** choose an explicit product policy (disable them in that mode or design a separately reviewed backup mechanism), not a silent fallback to weaker authorization.

Evidence: [MainActivity, `onSaveInstanceState` and the lock/relock paths](../app/src/main/kotlin/org/shadowice/flocke/andotp/Activities/MainActivity.kt) (Kotlin since the twenty-first batch; session and saved-state handling have since changed); [KeyStoreHelper, loadOrGenerateWrappedKey](../app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/KeyStoreHelper.kt) (Kotlin since the fifteenth batch; the key-generation policy described here is unchanged); [SecretKeyWrapper](../app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/SecretKeyWrapper.kt) (Kotlin since the third batch).

### F. Constrain intentional token/backup integrations — medium priority

- `EntriesCardAdapter.sendKeystrokes` sends an OTP in an implicit `ACTION_SEND` intent. Require informed recipient selection; consider a user-approved explicit component and signing-identity checks for a persistently trusted integration. Do not silently trust whichever handler resolves. OpenPGP also receives plaintext vault data for encryption: provider trust is part of the security model, not something Kotlin fixes.
- At the baseline, `Tools.copyToClipboard` copied plain text without a sensitive-content marker or app-managed timeout. It now marks clips sensitive but still has no app-managed expiration. Consider best-effort expiration only when the clipboard still contains this app's clip. Clipboard visibility/protections differ across Android versions; API 28 deserves particular attention.
- The exported encrypted-backup receiver is gated by user opt-in, password, location and encryption mode. WorkManager unique work with `KEEP` avoids simultaneous duplicates, but not repeated completed backups or notification spam. The receiver does not check the incoming action, and the worker does not recheck whether broadcast backups are still enabled. Validate action, recheck authorization at execution and define rate limits. Addressing a broadcast to a package is routing, not authorization. Signature permissions alone are not compatible with arbitrary third-party automation.
- Preserve default screenshot blocking (`Settings.getScreenshotsEnabled` defaults false). Review all secret-bearing windows, including dialogs. Evaluate obscured-touch rejection for sensitive actions and API-guarded overlay hiding on supported Android releases. Accessibility/autofill restrictions already exist for several credential fields; review coverage without treating them as complete protection against privileged services.

Evidence: [EntriesCardAdapter, `sendKeystrokes`](../app/src/main/kotlin/org/shadowice/flocke/andotp/View/EntriesCardAdapter.kt) (Kotlin since the twenty-first batch); [Tools.copyToClipboard](../app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/Tools.kt) (now Kotlin); [BackupActivity.backupEncryptedWithPGP](../app/src/main/kotlin/org/shadowice/flocke/andotp/Activities/BackupActivity.kt) (Kotlin since the twenty-first batch); [receiver](../app/src/main/kotlin/org/shadowice/flocke/andotp/Receivers/EncryptedBackupBroadcastReceiver.kt); [worker](../app/src/main/kotlin/org/shadowice/flocke/andotp/Tasks/EncryptedBackupWorker.kt); [AuthenticateActivity](../app/src/main/kotlin/org/shadowice/flocke/andotp/Activities/AuthenticateActivity.kt) (Kotlin since the twenty-first batch).

### G. Specify backup and cryptographic policy explicitly — medium/high priority

**Observed:** `allowBackup=true`, `fullBackupOnly=true`, a custom `BackupAgent`, and Android backup enabled by default in settings. `onFullBackup` honors the setting before calling the superclass, but there are no explicit `fullBackupContent`/`dataExtractionRules` declarations in the source manifest. The database key is a wrapped AES key, with a nonportable AndroidKeyStore dependency.

**Action:** Choose whether platform cloud backup and device-to-device transfer are permitted. Define explicit allowlists/exclusions for supported Android versions, separately from user-created encrypted exports; test restored preferences, wrapped-key files and encrypted vault together. Never claim that AndroidKeyStore keys will transfer. Retain recovery guidance and a verified portable encrypted-backup path. Actual inclusion and transport behavior require device/backup inspection, not assumptions from `FileBackupHelper` registration alone.

Keep AES-GCM, secure random nonces and JCA HMAC. Prefer explicit `GCMParameterSpec` after provider/format tests. Review the existing PBKDF2-HMAC-SHA1 work factors, short PIN policy, RSA-PKCS1 wrapping and old SHA-256-only backup import. These are design/compatibility decisions, not grounds for mechanical algorithm substitution. HMAC-SHA1 OTP interoperability is distinct from SHA-1 collision weaknesses; do not break RFC-compatible accounts to “upgrade” it. Likewise, retain legacy mOTP behavior only as an explicit compatibility feature. If upgrading backup KDFs, introduce a versioned format and preserve old readers; changing constants alone breaks existing files. Randomizing backup iterations within a narrow range is not a substitute for an appropriate work factor and strong passwords.

Evidence: [manifest](../app/src/main/AndroidManifest.xml); the [legacy `BackupAgent`](../app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/BackupAgent.kt) (currently unregistered); [Settings](../app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/Settings.kt) (Kotlin since the sixteenth batch; `getAndroidBackupServiceEnabled()` still returns `false` without reading storage and every commit still writes that flag off); [Constants](../core/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/Constants.kt) (Kotlin since the twenty-second batch; now in the shared `:core` module; the PBKDF2 and backup parameters are unchanged); [EncryptionHelper](../app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/EncryptionHelper.kt) (Kotlin since the fifteenth batch; PBKDF2 work factors, RSA-PKCS1 wrapping and the SHA-256 legacy import are unchanged).

## 4. Language conversion choices

| Conversion | Security benefit here | Android compatibility/cost | Decision |
| --- | --- | --- | --- |
| Java → Kotlin/JVM, incrementally | Better null contracts, sealed outcomes and immutable validated models; fewer invalid-state/lifecycle mistakes when deliberately redesigned | Native Android APIs, Java/JCA/WorkManager interop; no JNI/ABI split; no inherent min-SDK increase | **Recommended selectively** |
| Keep Java, improve types/tests | Can implement every urgent security fix above; already managed memory safety | Lowest migration cost; existing APIs/build remain familiar | **Strong fallback and immediate baseline** |
| Java → Rust for current crypto/OTP/parsers | Little incremental memory-safety gain over Java; does not secure IPC or UI policy | JNI/FFI errors and secret copies, NDK/ABI builds, native crash handling, 16-KB-page compatibility; KeyStore still needs platform integration | **Not justified now** |
| Java → C/C++ | No demonstrated benefit; introduces memory-corruption risks | NDK/toolchain and ABI burden | **Reject** |
| Java → Flutter/Dart or React Native/JS/TS | No inherent improvement to Android sandbox/trust boundaries; more bridges for security-sensitive features | Reimplement UI, lifecycle, key management and integrations; larger dependency/runtime surface | **Reject for these requirements** |
| Java → Go/Python/web runtime | No clear security advantage for this native offline app | Additional runtime/platform bridges and lifecycle/packaging work | **Reject** |
| Groovy Gradle → Kotlin DSL | Build-script type checking and IDE navigation, not runtime isolation | Script/plugin accessor migration and build churn; independent of Kotlin app code | **Optional, low priority; do separately** |
| XML Views → Compose | Potential UI maintainability benefit, not a language-security boundary | Broad UI/lifecycle/test migration | **Not required for Kotlin adoption** |

Rust could become appropriate for a future audited, shared native subsystem, or to replace a demonstrably memory-unsafe dependency. Reassess against measured parser complexity, fuzzing needs, team expertise and native costs then. Merely hiding seeds in a native library is not protection from a compromised process.

### Best conversion targets, in order

1. **Validated entry/import models:** split URI/JSON parsing from `Entry`'s mutable UI/token metadata. Build typed validated values and a closed parse-error set. Use private construction/factories; Kotlin `data class` syntax alone does not enforce invariants. Keep Java-callable adapters while callers migrate.
2. **Vault repository outcomes:** replace ambiguous list/boolean returns in `DatabaseHelper` and encryption-change paths with sealed outcomes and transactional operations. These semantics can and should be fixed in Java first if Kotlin setup would delay remediation.
3. **Authentication/vault session state:** replace scattered booleans/raw-key transfers with explicit locked/unlocking/unlocked/error states. Use a process-memory session plus lifecycle-aware ViewModels; do not persist its secrets through saved state. Reauthentication, not restoration of raw keys, is the process-death path.
4. **Task lifecycle:** replace `UiBasedBackgroundTask` and retained platform fragments with lifecycle-aware execution where appropriate. Coroutines offer structured cancellation, but a PBKDF2/provider call may not be interruptible. Preserve input limits and prevent stale task results from unlocking a newer/locked session. Keep WorkManager for durable background backup; Kotlin does not require replacing a working Java worker.
5. **UI/presentation:** convert activities/adapters only after smaller contracts are validated and their responsibilities have been decomposed and tested. `MainActivity` (~1,007 baseline lines) and `EntriesCardAdapter` (~840 baseline lines) are poor automatic-conversion targets. Keep extractions in Java initially; review language conversion separately. The UI has since been converted; the [UI decomposition plan](ui-decomposition-plan.md) tracks remaining extractions and acceptance gates.
6. **Crypto wrappers/OTP:** keep JCA primitives and proven behavior; convert last if it improves maintenance. Avoid a simultaneous algorithm/provider/file-format change. Keep byte arrays out of generated `toString`/logging, equality and serialization paths for secret-bearing models.

During mixed-language work, annotate Java null contracts, minimize platform types and Kotlin `!!`, avoid unchecked mutable collections across boundaries, and preserve Java-facing method signatures where needed. Converting a nullable platform API call to `!!` simply preserves its crash risk.

## 5. Android compatibility gates

- Maintain API 28 as the minimum unless product requirements explicitly change. Do not describe this as support for all Android devices: Android 9's platform security depends on vendor patch support, which a language migration cannot restore.
- Test API 28, 30, 31/32, 33, 34, 35 and 36, covering minimum support, package visibility, component/backup changes, notifications, newer target behavior, and latest compiled platform. `compileSdk 36` is not the same as opting into all target-36 behavior; raise the target only after deliberate testing.
- Test both `fdroid` and `play`, debug and release; inspect merged manifests/dependencies for each. Test physical devices with differing KeyStore implementations and credential/biometric changes as well as emulators.
- Retain Storage Access Framework URI permissions; exercise local, cloud and hostile providers, revoked grants and short writes. Legacy external-storage mounted-state checks should not be used as the sole availability test for SAF/cloud documents.
- Review deprecated device-credential/activity-result and retained-fragment APIs as separate modernization work. `BaseActivity` registers a screen-off receiver using the older overload; screen-off is a system broadcast, so do not assume the general Android 14 exported-flag rule means this specific call always fails. Test before changing flags.
- Choose and pin a Kotlin plugin version compatible with this AGP/Gradle setup, align Java/Kotlin bytecode targets, and verify release desugaring. No unsupported exact Kotlin version is prescribed by this static review.
- Verify dependency minimum-SDK and compile-SDK requirements through actual resolution/build. Version declarations alone do not establish that the current dependency set builds. Pin/review JitPack dependencies, add dependency verification/locking where appropriate, and verify the Gradle distribution checksum.
- If a future library adds native `.so` files, inspect ABI coverage and current 16-KB-page requirements even if app source remains Kotlin/Java.

## 6. Practical rollout and acceptance criteria

### Phase 0 — establish a reproducible baseline

Install/configure a supported JDK and Android SDK in CI. Build/lint both flavors and run the existing instrumentation vectors. Add fast host-side tests for separable domain logic. Baseline existing lint findings, then fail on new security-relevant errors. Record release merged-manifest/component inventories and dependency provenance.

### Phase 1 — harden current trust boundaries without waiting for conversion

Resolve panic authorization, external-entry confirmation, bounded inputs/KDF work, fail-closed vault loads/atomic writes, session key lifetime and backup policy. Preserve old-format encrypted fixtures and untouched recovery backups. Do not combine storage format changes with language conversion.

### Phase 2 — pilot Kotlin at a narrow boundary

**Implementation update:** The [OTP parser](kotlin-otp-parser-pilot.md), entry model, session/task
contracts and UI were subsequently converted to Kotlin. External `ACTION_VIEW` imports now ask
for confirmation. The [conversion record](kotlin-extracted-helpers.md) documents the batches;
current builds, lint, packaging and device checks remain release gates. The rollout below is the
original recommendation.

Add Kotlin and migrate a bounded OTP parser/validator plus tests, keeping a Java façade. Compare old/new behavior on valid fixtures and intentionally document stricter rejection of invalid input. Continue only if interoperability, build complexity and maintenance ownership are acceptable.

### Phase 3 — migrate repository/session/task contracts

Move toward explicit results and lifecycle state, then UI classes opportunistically. Scope each change for independent review and rollback; avoid a single bulk auto-conversion commit.

### Required checks before shipping

- Existing RFC TOTP/HOTP and AES-GCM vectors remain identical; Steam/mOTP and leading-zero behavior are covered.
- Old/current backup formats decrypt correctly, Unicode round-trips, and new exports remain interoperable with intended external readers. Test wrong passwords, truncated data, modified GCM tags, extreme iteration headers and unsupported formats.
- Invalid URI/JSON parameters, empty/invalid secrets, zero/negative periods, extreme digits/counters, duplicate parameters, huge input and malformed images are rejected within explicit resource budgets.
- A failed load, partial parse, failed write or failed migration cannot produce a success backup or overwrite the last recoverable vault. Simulate process death/power-loss boundaries and revoked provider access.
- Independently signed test apps cannot invoke unauthorized destructive operations or silently receive OTPs; verify intended automation still works under the chosen authorization policy.
- Rotation, recreation, background/foreground, screen-off, process death, concurrent auth attempts and stale callbacks cannot bypass authentication or restore a secret from saved state. Test unavailable/invalidated KeyStore keys and changed device credentials.
- Unattended backup behavior is explicitly tested against the chosen authentication-bound-key policy; queued work rechecks opt-in and fails closed on key/load errors.
- Cloud backup and device-transfer contents follow policy and either restore safely or give clear recovery instructions. Do not infer transfer safety from encryption alone.
- Both release manifests remain offline and expose only reviewed components. Inspect actual packaged permissions and native libraries.

**Bottom line:** the best destination is a small, well-tested native Android Java/Kotlin codebase with deliberate IPC, input, backup and key-lifecycle policies. A Kotlin migration supports those policies; it does not replace them. A full rewrite, particularly one adding a native runtime, would spend the project's limited maintenance budget without addressing the main observed threats.

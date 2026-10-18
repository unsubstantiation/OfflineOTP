# Kotlin pilot: a validated OTP URI boundary

## Scope and status

The pilot consists of `OtpUriParser.kt`, `OtpParseResult.kt`, and their host tests. It replaces only the URI parsing inside `Entry(String)`. At the time of the pilot, UI classes, JSON/backup formats, OTP calculation, JCA crypto, vault/session storage and WorkManager remained Java. Subsequent conversion work moved the app main sources to Kotlin; see the conversion record for current scope. The subsequent [vault/session/task contract migration](kotlin-vault-session-task-contracts.md) adds typed Kotlin contracts without moving their Android/storage adapters. No Compose, coroutine, serialization, JNI or native-runtime migration is included.

**Implementation and host tests are present; Gradle and device acceptance are still CI gates.**
The host suite compiles and passes in the sandbox, and the instrumentation sources compile
against stand-ins, but this environment has no Android SDK or Gradle dependency resolution.
Python policy tests are not substitutes for a Gradle build or Android tests; do not treat this
pilot as release-accepted until the both-flavor CI and API 28/36 instrumentation gates pass.

## Contracts

`OtpUriParser.parse(String?)` is Android-free, has a Java static entry point and returns a closed result hierarchy:

- `OtpParseResult.Success`: an owned `ValidatedOtp` value with immutable metadata.
- `OtpParseResult.Failure`: one of the finite `OtpParseError` enum values, with no raw URI, seed, account label or provider exception message attached.

“Validated” describes syntax and bounds, not proof of account/issuer authenticity; imported accounts still need user review.

Expected malformed input produces a failure, not an exception. The internal rejection exception is not an API payload; programming/resource failures are not indiscriminately swallowed as input errors.

`ValidatedOtp` has private construction with invariant checks, read-only metadata, a defensively copied/unmodifiable tag list, and a private seed. It is deliberately **not a data class**: no generated secret-based equality, hash, `copy`, destructuring, or diagnostic output. Both value and success-result `toString()` are redacted. `copySecret()` returns an owned copy; `close()` wipes the internal array best-effort and rejects further secret copies. Callers must dispose of their own copies. Source URI/decoded Strings and framework copies cannot be reliably erased.

Kotlin consumers can exhaustively handle the result:

```kotlin
when (val result = OtpUriParser.parse(uri)) {
    is OtpParseResult.Failure -> showParseError(result.error)
    is OtpParseResult.Success -> result.value.use { value ->
        reviewAccount(value.issuer, value.label) // illustrative consumer functions
    }
}
```

Java consumers use `OtpUriParser.parse(uri)`, `instanceof OtpParseResult.Success/Failure`, and `getValue()/getError()`. `Entry(String)` is the compatibility adapter: it copies the validated fields/seed into the existing Java model, closes the temporary value and, for its legacy callers, translates a failure into a sanitized exception containing only the error enum. It does not contain a second URI parser. Existing UI callers therefore gain the new validation without changing their APIs; migrating them to direct typed-result handling is a separate follow-up.

## Validation and compatibility

The parser uses `java.net.URI`, strict one-pass percent/UTF-8 decoding, the existing Commons Codec Base32 implementation, and the existing `InputPolicy` limits. It has no Android context, provider, network, global entry state or crypto-provider dependency.

Preserved behavior within the limits:

- TOTP, HOTP, Steam and mOTP URI variants; SHA1/SHA256/SHA512 and case-insensitive algorithm names.
- RFC defaults (30-second period, 6 digits; Steam 5), custom 1–9 digit OTPs, HOTP counters below `Long.MAX_VALUE` and periods up to 86,400 seconds.
- Canonical padded/unpadded ASCII Base32, ASCII lowercase seeds, repeated tags and unknown **unique** query parameters.
- Issuer-prefix stripping only when an explicit matching issuer exists; no issuer inference from label text. Missing issuer is normalized to the empty string instead of a Java platform null.
- Valid unused numeric fields keep their historical ignored/default semantics, but still must pass validation.

Intentional stricter rejections:

- Malformed percent escapes, malformed UTF-8, unpaired UTF-16 surrogates and unescaped URI spaces (encode spaces as `%20`). No replacement-character repair or double decoding.
- Duplicate singleton names **after decoding**, including differently encoded spellings of `secret`; empty query segments/names.
- Malformed, signed-positive, overflowing or out-of-range numeric parameters, including fields unused by the selected OTP variant.
- Invalid/noncanonical Base32 and Unicode characters that merely uppercase into ASCII Base32. mOTP seeds must be nonempty, even-length ASCII hex, as the existing manual-entry validator expects; letter case is preserved because it affects mOTP input bytes.
- Steam digit counts other than five and all existing size/tag/seed bounds.

Query `+` means space consistently (including repeated tags); `%2B` means a literal plus. Path `+` remains literal. This removes differences between Android query helper overloads. Generated andOTP URIs percent-encode values, so their literal plus signs remain intact. Existing malformed mOTP URIs or permissively encoded external labels may now fail; the parser does not silently rewrite them.

Backup framing, database schema, KDF settings and token algorithms are **not** changed by this pilot. The JSON/document path was not converted by this pilot either; it became Kotlin later, in the entry batch of the [conversion record](kotlin-extracted-helpers.md) (`EntryJson`/`EntryJsonResult`, with `Entry` reduced to an adapter), where the malformed-input and canonical-secret rules above were applied to the document path as well.

## Build changes and provenance

- Kotlin Gradle plugin **2.4.21** and a strictly aligned **2.4.21** stdlib declaration; Groovy build scripts are retained.
- Kotlin JVM target **1.8**, matching Java 8 bytecode; minimum API **28** and target API **35** unchanged.
- Compiler/build-tools/plugin classpaths are included in the existing dependency inventory, in addition to the root buildscript and app/runtime/test classpaths. Missing compiler configuration fails the inventory gate instead of silently omitting compiler inputs.
- No Kotlin plugin is applied outside the app module.

Selection sources: [official Kotlin 2.4.21 release](https://github.com/JetBrains/kotlin/releases/tag/v2.4.21), and the [KGP compatibility table](https://github.com/JetBrains/kotlin-web-site/blob/master/docs/topics/gradle/gradle-configure-project.md). The 2.4.20–2.4.21 row covers Gradle 7.6.3–9.7.0 and AGP 8.5.2–9.3.1, including this repository's Gradle 8.13/AGP 8.13.2. Compiler configuration names were checked against the [tagged KGP source](https://github.com/JetBrains/kotlin/blob/v2.4.21/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/plugin/AbstractKotlinPlugin.kt). Compatibility declarations are not evidence that this project actually builds.

The new resolved Kotlin inputs need the same trusted lockfile/checksum baseline review as the rest of the build. No dependency locks or verification checksums have been fabricated. Independent unsigned APK and dependency-inventory comparisons must be rerun; repeatability is not assumed from adding a pinned version.

## Tests and acceptance

`OtpUriParserTest` is a JVM JUnit suite using production parsing and Commons Codec, without Android mocks. It covers defaults/RFC seed bytes, all four variants, custom numeric boundaries, Unicode/plus/issuer semantics, decoded singleton collisions, malformed escapes/UTF-8, numeric overflow, canonical Base32, URI/text/tag/decoded-seed limits, redaction, owned bytes/tags, close behavior, builder invariants and a deterministic malformed-input corpus.

The current instrumentation suite is the four Kotlin tests under `app/src/androidTest/kotlin`;
it covers the platform-only URI/document paths, RFC TOTP/HOTP vectors, AES-GCM provider behavior,
the database round trip, app context and first-run launch. The `KotlinOtpInteropTest` and larger
interop suites described in earlier revisions are not part of this checkout. Source-policy tests
separately enforce the Kotlin pins, aligned JVM targets and Android-free scope; they do not
execute Kotlin.

Run both flavors in the pinned JDK/SDK environment:

```sh
python3 -m unittest discover -s scripts/tests -v
./gradlew --no-daemon :app:testFdroidDebugUnitTest :app:testPlayDebugUnitTest \
  :app:lintFdroidRelease :app:lintPlayRelease \
  :app:assembleFdroidDebug :app:assemblePlayDebug
bash scripts/verify_reproducible_build.sh

# Disposable API 28/36 emulator only.
./gradlew --no-daemon :app:connectedFdroidDebugAndroidTest
./gradlew --no-daemon :app:connectedPlayDebugAndroidTest
```

Continue Kotlin adoption only after these gates pass and the result/ownership APIs are reviewed. Kotlin makes this boundary easier to express; it is not itself IPC authorization, memory erasure, supply-chain verification, or a security audit.

"""Guards for the completed unified OpenPGP backend (rpgp + UniFFI, 5/5).

The fifth gap was that three clients used three incompatible OpenPGP stacks:
Android via an external OpenKeychain provider, the desktop via embedded PGPainless
(JVM-only), and iOS with none. The fix is a single Rust crate
(`rust/offlineotp-openpgp`, rpgp 0.21 + UniFFI) that builds a cdylib for JVM
targets (JNA) and a staticlib/XCFramework for Apple targets. These checks fail
when the crate, its generated bindings, its adapters, or its packaging drift back
into a scaffold. They are source/packaging guards, not a substitute for the
crate's native security tests (which run in CI).
"""
import os
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUST_WORKSPACE = ROOT / "rust/Cargo.toml"
RUST_CRATE = ROOT / "rust/offlineotp-openpgp/Cargo.toml"
RUST_LIB = ROOT / "rust/offlineotp-openpgp/src/lib.rs"
RUST_BUILD = ROOT / "rust/offlineotp-openpgp/build.rs"
RUST_UDL = ROOT / "rust/offlineotp-openpgp/src/offlineotp_openpgp.udl"


class OpenPgpRustCrateTest(unittest.TestCase):

    def test_workspace_exists_and_members_crate(self):
        self.assertTrue(RUST_WORKSPACE.is_file(), "rust/Cargo.toml workspace must exist")
        text = RUST_WORKSPACE.read_text(encoding="utf-8")
        self.assertIn('members = ["offlineotp-openpgp"]', text)
        self.assertIn('resolver = "2"', text)

    def test_crate_manifest_is_pinned_and_declares_uniffi_outputs(self):
        self.assertTrue(RUST_CRATE.is_file(), "rust/offlineotp-openpgp/Cargo.toml must exist")
        text = RUST_CRATE.read_text(encoding="utf-8")
        self.assertIn('name = "offlineotp_openpgp"', text)
        self.assertIn('crate-type = ["cdylib", "staticlib", "lib"]', text)
        self.assertIn('rust-version = "1.88"', text)
        self.assertIn('pgp = { version = "=0.21.0"', text)
        self.assertIn('uniffi = "=0.28.3"', text)
        self.assertIn('thiserror = "=2.0', text)
        self.assertIn('rand = "=0.8', text)
        self.assertIn('zeroize = "=1.8', text)
        self.assertIn("publish = false", text)

    def test_udl_contract_and_build_script_generate_scaffolding(self):
        self.assertTrue(RUST_UDL.is_file(), "the UDL contract must exist")
        udl = RUST_UDL.read_text(encoding="utf-8")
        self.assertIn("namespace offlineotp_openpgp", udl)
        self.assertIn("sequence<u8> encrypt", udl)
        self.assertIn("sequence<u8> decrypt", udl)
        self.assertIn("[Error]", udl)
        self.assertIn("InvalidRecipientKey", udl)
        self.assertIn("DecryptionFailed", udl)
        self.assertTrue(RUST_BUILD.is_file(), "build.rs must generate UniFFI scaffolding")
        text = RUST_BUILD.read_text(encoding="utf-8")
        self.assertIn('uniffi::generate_scaffolding("src/offlineotp_openpgp.udl")', text)

    def test_lib_implements_real_crypto_and_respects_bounds(self):
        self.assertTrue(RUST_LIB.is_file(), "rust/offlineotp-openpgp/src/lib.rs must exist")
        text = RUST_LIB.read_text(encoding="utf-8")
        self.assertIn('uniffi::include_scaffolding!("offlineotp_openpgp")', text)
        self.assertIn("pub enum OpenPgpError", text)
        self.assertIn("pub fn encrypt", text)
        self.assertIn("pub fn decrypt", text)
        self.assertIn("pub fn rpgp_version", text)
        self.assertIn("pub fn crate_version", text)
        self.assertIn("MAX_DOCUMENT_BYTES", text)
        self.assertIn("16 * 1024 * 1024", text)
        self.assertIn("MAX_COMPRESSION_LAYERS", text)
        self.assertIn("SymmetricKeyAlgorithm::AES256", text)
        self.assertIn("seipd_v1", text)
        self.assertIn("SignedPublicKey::from_reader_single", text)
        self.assertIn("SignedSecretKey::from_reader_single", text)
        self.assertIn("verify_bindings", text)
        self.assertIn("Message::from_bytes", text)
        self.assertIn("Message::from_armor", text)
        self.assertIn("decrypt(&password", text)
        self.assertIn("is_compressed", text)
        self.assertIn("decompress()", text)
        # The bounded read replaces the unbounded as_data_vec() convenience.
        self.assertNotIn("as_data_vec()", text)
        self.assertIn("EmptyOutput", text)
        self.assertIn("TooLarge", text)
        self.assertIn("SignatureType::KeyRevocation", text)
        self.assertIn("SignatureType::SubkeyRevocation", text)
        self.assertIn("expiration_elapsed", text)
        # Security regression corpus.
        for name in (
            "round_trip_single_recipient",
            "round_trip_two_recipients",
            "armored_keys_and_message_round_trip",
            "compressed_message_round_trips",
            "rejects_empty_recipient_set",
            "rejects_wrong_secret_key",
            "passphrase_protected_key_requires_the_right_passphrase",
            "rejects_plaintext_and_marker_files",
            "rejects_tampered_ciphertext",
            "rejects_blank_output",
            "enforces_document_bounds",
        ):
            self.assertIn(name, text)

    def test_uniffi_config_points_at_the_packaged_library_name(self):
        config = ROOT / "rust/offlineotp-openpgp/uniffi.toml"
        self.assertTrue(config.is_file())
        text = config.read_text(encoding="utf-8")
        self.assertIn('[bindings.kotlin]', text)
        self.assertIn('cdylib_name = "offlineotp_openpgp"', text)
        kotlin = (ROOT / "rust/bindings/kotlin/uniffi/offlineotp_openpgp/offlineotp_openpgp.kt").read_text(encoding="utf-8")
        self.assertIn('return "offlineotp_openpgp"', kotlin)
        self.assertNotIn('return "uniffi_offlineotp_openpgp"', kotlin)

    def test_generated_bindings_and_native_facade_are_committed(self):
        kotlin = ROOT / "rust/bindings/kotlin/uniffi/offlineotp_openpgp/offlineotp_openpgp.kt"
        facade = ROOT / "rust/bindings/kotlin/uniffi/offlineotp_openpgp/NativeApi.kt"
        swift = ROOT / "ios/Sources/offlineotp_openpgp/offlineotp_openpgp.swift"
        header = ROOT / "rust/bindings/swift/offlineotp_openpgpFFI.h"
        modulemap = ROOT / "rust/bindings/swift/offlineotp_openpgpFFI.modulemap"
        for path in (kotlin, facade, swift, header, modulemap):
            self.assertTrue(path.is_file(), f"missing committed binding {path}")
        kt = kotlin.read_text(encoding="utf-8")
        self.assertIn("package uniffi.offlineotp_openpgp", kt)
        self.assertIn("fun `encrypt`", kt)
        self.assertIn("fun `decrypt`", kt)
        native = facade.read_text(encoding="utf-8")
        self.assertIn("object NativeApi", native)
        self.assertIn("fun isAvailable()", native)
        self.assertIn("ByteArray", native)
        sw = swift.read_text(encoding="utf-8")
        self.assertIn("public func encrypt", sw)
        self.assertIn("public func decrypt", sw)
        self.assertIn("offlineotp_openpgpFFI", sw)

    def test_jvm_adapters_use_the_native_facade_and_fallback_gracefully(self):
        desktop = ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/openpgp/RpgpOpenPgpPort.kt"
        android = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/openpgp/RpgpOpenPgpPort.kt"
        self.assertTrue(desktop.is_file(), "desktop RpgpOpenPgpPort must exist")
        self.assertTrue(android.is_file(), "android RpgpOpenPgpPort must exist")
        for path in (desktop, android):
            t = path.read_text(encoding="utf-8")
            self.assertIn("class RpgpOpenPgpPort", t)
            self.assertIn(": OpenPgpPort", t)
            self.assertIn("uniffi.offlineotp_openpgp.NativeApi", t)
            self.assertIn("tryLoadNative", t)
            self.assertIn("isAvailable", t)
            self.assertIn("InvocationTargetException", t)

        d_text = desktop.read_text(encoding="utf-8")
        self.assertIn("PgpainlessOpenPgpPort", d_text)
        self.assertIn("PgpainlessOpenPgpPort()", d_text)
        self.assertIn("fallback", d_text.lower())

        a_text = android.read_text(encoding="utf-8")
        self.assertIn("Unified OpenPGP backend", a_text)
        self.assertIn("OpenKeychain", a_text)

        coordinator = ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control/BackupCoordinator.kt"
        c_text = coordinator.read_text(encoding="utf-8")
        self.assertIn("private val pgpPort: OpenPgpPort = RpgpOpenPgpPort()", c_text)

    def test_jvm_gradle_builds_compile_bindings_and_jna(self):
        desktop = (ROOT / "desktop/build.gradle").read_text(encoding="utf-8")
        android = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        self.assertIn('kotlin.srcDir("$rootDir/rust/bindings/kotlin")', desktop)
        self.assertIn("net.java.dev.jna:jna:5.14.0", desktop)
        self.assertIn("stageOpenPgpNative", desktop)
        self.assertIn("-Djna.library.path=$APPDIR/native", desktop)
        self.assertIn('java.srcDir "$rootDir/rust/bindings/kotlin"', android)
        self.assertIn("net.java.dev.jna:jna:5.14.0@aar", android)
        self.assertIn("jniLibs.srcDir 'src/main/jniLibs'", android)

    def test_ios_adapter_calls_the_generated_bindings_when_packed(self):
        swift = ROOT / "ios/Sources/OfflineOTPKit/OpenPgp.swift"
        self.assertTrue(swift.is_file(), "ios OpenPgp.swift must exist")
        t = swift.read_text(encoding="utf-8")
        self.assertIn("protocol OpenPgpPort", t)
        self.assertIn("enum OpenPgpBackupCodec", t)
        self.assertIn("struct RustOpenPgpPort", t)
        self.assertIn("OpenPgpError", t)
        self.assertIn("OpenPgpAvailability", t)
        self.assertIn("maxDocumentBytes", t)
        self.assertIn("emptyoutput", t.lower())
        self.assertIn("toolarge", t.lower())
        self.assertIn("OFFLINEOTP_OPENPGP_NATIVE", t)
        self.assertIn("import offlineotp_openpgp", t)
        self.assertIn("offlineotp_openpgp.encrypt", t)
        self.assertIn("offlineotp_openpgp.decrypt", t)
        self.assertIn("backendUnavailable", t)
        self.assertIn("XCFramework", t)
        self.assertIn("seal(port:", t)
        self.assertIn("open(port:", t)

        package = (ROOT / "ios/Package.swift").read_text(encoding="utf-8")
        self.assertIn("Frameworks/offlineotp_openpgp.xcframework", package)
        self.assertIn("OFFLINEOTP_OPENPGP_NATIVE", package)
        self.assertIn('name: "offlineotp_openpgp"', package)
        self.assertIn('name: "offlineotp_openpgpFFI"', package)

    def test_packaging_scripts_exist_and_are_executable(self):
        scripts = ROOT / "scripts/openpgp"
        for name in ("generate-bindings.sh", "build-ios-xcframework.sh", "build-android-jnilibs.sh", "README.md"):
            path = scripts / name
            self.assertTrue(path.is_file(), f"missing {path}")
            if name.endswith(".sh"):
                self.assertTrue(os.access(path, os.X_OK), f"{path} must be executable")
        gen = (scripts / "generate-bindings.sh").read_text(encoding="utf-8")
        self.assertIn("--check", gen)
        self.assertIn("offlineotp_openpgp.udl", gen)
        ios = (scripts / "build-ios-xcframework.sh").read_text(encoding="utf-8")
        self.assertIn("xcodebuild -create-xcframework", ios)
        self.assertIn("aarch64-apple-ios", ios)
        android = (scripts / "build-android-jnilibs.sh").read_text(encoding="utf-8")
        self.assertIn("cargo ndk", android)
        self.assertIn("app/src/main/jniLibs", android)
        # `cargo ndk` cross-compiles with the toolchain rustup already has; it does not install
        # `std` for the Android ABIs. The iOS script has always run `rustup target add`; the
        # Android one did not, so a clean runner failed with "can't find crate for `std`". Both
        # scripts must now be self-contained, and every ABI they build must have its target.
        self.assertIn("rustup target add", android)
        for target in ("aarch64-linux-android", "armv7-linux-androideabi", "x86_64-linux-android"):
            self.assertIn(target, android)
        self.assertIn("rustup target add", (scripts / "build-ios-xcframework.sh").read_text(encoding="utf-8"))
        ignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        self.assertIn("/rust/target/", ignore)
        self.assertIn("/app/src/main/jniLibs/", ignore)
        self.assertIn("/ios/Frameworks/", ignore)

    def test_every_workflow_tests_and_builds_the_rust_crate(self):
        workflows = ROOT / ".github/workflows"
        for name in ("android.yml", "debian.yml", "windows.yml", "macos.yml", "ios.yml"):
            text = (workflows / name).read_text(encoding="utf-8")
            self.assertIn("cargo test --manifest-path rust/Cargo.toml", text,
                          f"{name} must run the crate's native tests")
            self.assertIn("cargo build --manifest-path rust/Cargo.toml --release", text,
                          f"{name} must build the release crate")
            self.assertTrue(
                "dtolnay/rust-toolchain@" in text or "sh.rustup.rs" in text or "rustup" in text,
                f"{name} must install the Rust toolchain"
            )
            self.assertIn("contents: read", text)
            self.assertIn("SHA256SUMS.txt", text)
        android = (workflows / "android.yml").read_text(encoding="utf-8")
        self.assertIn("build-android-jnilibs.sh", android)
        self.assertIn("ndk;", android)
        # A bare `test -d "$ANDROID_NDK_HOME"` was the only thing standing between a
        # differently-laid-out NDK and an unexplained exit 1; the script now resolves the NDK and
        # says which paths it tried.
        self.assertNotIn('test -d "$ANDROID_NDK_HOME"', android)
        ios = (workflows / "ios.yml").read_text(encoding="utf-8")
        self.assertIn("build-ios-xcframework.sh", ios)

    def test_docs_record_completion_and_honest_limits(self):
        plan = (ROOT / "docs/ios-client-plan.md").read_text(encoding="utf-8")
        self.assertIn("rust/offlineotp-openpgp", plan)
        self.assertIn("rpgp 0.21 + UniFFI", plan)
        self.assertIn("OFFLINEOTP_OPENPGP_NATIVE", plan)
        self.assertIn("backendUnavailable", plan)
        self.assertIn("## Non-goals for v1", plan)
        self.assertIn("Android via external OpenKeychain provider, desktop via embedded PGPainless, iOS with", plan)
        self.assertIn("none). That gap is now closed", plan)
        self.assertIn("one implementation for all five distributions", plan)

        parity = (ROOT / "docs/client-parity-2026-11-02.md").read_text(encoding="utf-8")
        self.assertIn("Two things are deliberately *not* the same", parity)
        self.assertNotIn("Three things are deliberately *not* the same", parity)
        self.assertIn("rust/offlineotp-openpgp", parity)
        self.assertIn("rpgp 0.21 + UniFFI", parity)
        self.assertIn("one implementation for all five distributions", parity)
        self.assertIn("That is closed by", parity)

        changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
        self.assertIn("OpenPGP: Complete the unified Rust backend", changelog)
        self.assertIn("rust/offlineotp-openpgp", changelog)
        self.assertIn("rpgp 0.21 + UniFFI", changelog)
        self.assertIn("five distributions (Android, Debian, Windows, macOS, iOS)", changelog)
        self.assertIn("cargo test", changelog.lower())
        self.assertIn("OFFLINEOTP_OPENPGP_NATIVE", changelog)

        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn("rust/offlineotp-openpgp", readme)
        self.assertIn("rpgp 0.21 + UniFFI, 5/5", readme)
        self.assertIn("org.pgpainless:pgpainless-core:2.0.4", readme)
        self.assertIn("default desktop engine", readme)
        self.assertIn("fallback when the native library is absent", readme)

        audit = ROOT / "docs/incomplete-feature-audit-2026-11-06.md"
        self.assertTrue(audit.is_file())
        self.assertIn("Verification limits", audit.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()

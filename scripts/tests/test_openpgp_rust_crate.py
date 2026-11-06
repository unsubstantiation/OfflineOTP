"""Guards for the unified OpenPGP backend (rpgp + UniFFI, 5/5).

The fifth gap was that three clients used three incompatible OpenPGP stacks:
Android via an external OpenKeychain provider, the desktop via embedded PGPainless
(JVM-only), and iOS with none. The fix is a single Rust crate
(`rust/offlineotp-openpgp`, rpgp 0.21 + UniFFI) that builds a cdylib for JVM
targets (JNA) and a staticlib/XCFramework for Apple targets, with
`RpgpOpenPgpPort` on the JVM and `RustOpenPgpPort` on iOS wrapping it. Each
per-platform workflow now builds and tests the crate on the host; per-ABI
packaging and removal of PGPainless are the remaining steps, not a design gap.
These checks fail when the crate, its adapters, or its workflow wiring drift.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUST_WORKSPACE = ROOT / "rust/Cargo.toml"
RUST_CRATE = ROOT / "rust/offlineotp-openpgp/Cargo.toml"
RUST_LIB = ROOT / "rust/offlineotp-openpgp/src/lib.rs"
RUST_BUILD = ROOT / "rust/offlineotp-openpgp/build.rs"


class OpenPgpRustCrateTest(unittest.TestCase):

    def test_workspace_exists_and_members_crate(self):
        self.assertTrue(RUST_WORKSPACE.is_file(), "rust/Cargo.toml workspace must exist")
        text = RUST_WORKSPACE.read_text(encoding="utf-8")
        self.assertIn('members = ["offlineotp-openpgp"]', text)
        self.assertIn('resolver = "2"', text)

    def test_crate_manifest_is_pinned_and_declares_uniffi_outputs(self):
        self.assertTrue(RUST_CRATE.is_file(), "rust/offlineotp-openpgp/Cargo.toml must exist")
        text = RUST_CRATE.read_text(encoding="utf-8")
        self.assertIn('name = "offlineotp-openpgp"', text)
        self.assertIn('crate-type = ["cdylib", "staticlib", "lib"]', text)
        self.assertIn('rust-version = "1.88"', text)
        # Vetted dependencies: rpgp 0.21 is the permissively licensed pure-Rust
        # OpenPGP implementation (MIT/Apache), UniFFI 0.28 is the Mozilla-maintained
        # binding generator. Both are the 5/5 choice (JVM via JNA, iOS via XCFramework).
        self.assertIn('pgp = { version = "0.21"', text)
        self.assertIn('uniffi = { version = "0.28"', text)
        self.assertIn('thiserror = "2.0"', text)
        self.assertIn('rand = "0.8"', text)
        self.assertIn("publish = false", text)

    def test_build_script_generates_uniffi_scaffolding(self):
        self.assertTrue(RUST_BUILD.is_file(), "build.rs must exist to generate UniFFI scaffolding")
        text = RUST_BUILD.read_text(encoding="utf-8")
        self.assertIn("generate_scaffolding", text)
        self.assertIn("uniffi", text)

    def test_lib_declares_unified_port_and_respects_bounds(self):
        self.assertTrue(RUST_LIB.is_file(), "rust/offlineotp-openpgp/src/lib.rs must exist")
        text = RUST_LIB.read_text(encoding="utf-8")
        # UniFFI entry point: one function per OpenPgpPort method, same document limits
        # as the Kotlin/Swift codec so only the outer envelope differs from password backups.
        self.assertIn('uniffi::setup_scaffolding!("offlineotp_openpgp")', text)
        self.assertIn("pub enum OpenPgpError", text)
        self.assertIn("pub fn encrypt", text)
        self.assertIn("pub fn decrypt", text)
        self.assertIn("pub fn rpgp_version", text)
        self.assertIn("pub fn crate_version", text)
        self.assertIn("MAX_DOCUMENT_BYTES", text)
        self.assertIn("16 * 1024 * 1024", text)
        self.assertIn("SymmetricKeyAlgorithm::AES256", text)
        self.assertIn("seipd_v1", text)
        # rpgp types: public key parsing handles both armored and binary, secret key
        # verification, message decrypt with Password, and decompression.
        self.assertIn("SignedPublicKey::from_reader_single", text)
        self.assertIn("SignedSecretKey::from_reader_single", text)
        self.assertIn("verify_bindings", text)
        self.assertIn("Message::from_bytes", text)
        self.assertIn("decrypt(&password", text)
        self.assertIn("is_compressed", text)
        self.assertIn("as_data_vec", text)
        # Document limits and empty-output rules mirror OpenPgpBackupCodec / OpenPgpOutputPolicy
        self.assertIn("EmptyOutput", text)
        self.assertIn("TooLarge", text)
        # The crate's unit tests are the corpus for the upcoming interop fixture:
        # encrypt with PGPainless, decrypt with rpgp, and vice versa (ed25519).
        self.assertIn("round_trip_single_recipient", text)
        self.assertIn("round_trip_two_recipients", text)
        self.assertIn("rejects_empty_recipient_set", text)

    def test_jvm_adapters_exist_and_fallback_gracefully(self):
        desktop = ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop/openpgp/RpgpOpenPgpPort.kt"
        android = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/openpgp/RpgpOpenPgpPort.kt"
        self.assertTrue(desktop.is_file(), "desktop RpgpOpenPgpPort must exist")
        self.assertTrue(android.is_file(), "android RpgpOpenPgpPort must exist")
        for path in (desktop, android):
            t = path.read_text(encoding="utf-8")
            self.assertIn("class RpgpOpenPgpPort", t)
            self.assertIn(": OpenPgpPort", t)
            self.assertIn("uniffi.offlineotp_openpgp", t)
            self.assertIn("offlineotp_openpgpKt", t)
            self.assertIn("tryLoadNative", t)
            self.assertTrue("fallback" in t.lower() or "openkeychain" in t.lower())

        # Desktop keeps PGPainless as a fallback while the native lib is optional;
        # once bindings are committed it will be removed.
        d_text = desktop.read_text(encoding="utf-8")
        self.assertIn("PgpainlessOpenPgpPort", d_text)
        self.assertIn("PgpainlessOpenPgpPort()", d_text)

        a_text = android.read_text(encoding="utf-8")
        self.assertIn("Unified OpenPGP backend", a_text)
        self.assertIn("OpenKeychain", a_text)

    def test_ios_adapter_exists_and_is_policy_correct(self):
        swift = ROOT / "ios/Sources/OfflineOTPKit/OpenPgp.swift"
        self.assertTrue(swift.is_file(), "ios OpenPgp.swift must exist")
        t = swift.read_text(encoding="utf-8")
        self.assertIn("protocol OpenPgpPort", t)
        self.assertIn("enum OpenPgpBackupCodec", t)
        self.assertIn("struct RustOpenPgpPort", t)
        self.assertIn("OpenPgpError", t)
        self.assertIn("OpenPgpAvailability", t)
        self.assertTrue("max_document_bytes" in t.lower() or "maxDocumentBytes" in t)
        # The Swift codec mirrors the Kotlin BoundedInputReader / OpenPgpOutputPolicy rules:
        self.assertTrue("emptyoutput" in t.lower())
        self.assertTrue("toolarge" in t.lower())
        self.assertTrue("unified rust backend" in t.lower())
        self.assertIn("rust/offlineotp-openpgp", t)
        self.assertIn("XCFramework", t)
        self.assertIn("seal(port:", t)
        self.assertIn("open(port:", t)

    def test_every_workflow_builds_and_tests_the_rust_crate(self):
        workflows = ROOT / ".github/workflows"
        for name in ("android.yml", "debian.yml", "windows.yml", "macos.yml", "ios.yml"):
            text = (workflows / name).read_text(encoding="utf-8")
            # Each per-platform workflow must now build the unified backend on the host
            # (cargo test + cargo build). Per-ABI packaging (cargo ndk, XCFramework) is a
            # follow-up, but host coverage must not be missing on any platform.
            self.assertIn("cargo test --manifest-path rust/Cargo.toml", text,
                          f"{name} must run cargo test on the Rust crate")
            self.assertIn("cargo build --manifest-path rust/Cargo.toml", text,
                          f"{name} must build the Rust crate")
            # Rust toolchain must be present — either via dtolnay pin or a rustup shell install
            # (Debian container uses rustup directly). A workflow with cargo but no toolchain
            # would fail with \"command not found\" instead of a clear setup error.
            self.assertTrue(
                "dtolnay/rust-toolchain@" in text or "sh.rustup.rs" in text or "rustup" in text,
                f"{name} must install the Rust toolchain"
            )
            # Still a read-only, artifact-only workflow (no release publishing)
            self.assertIn("contents: read", text)
            self.assertIn("SHA256SUMS.txt", text)

    def test_ios_plan_no_longer_claims_openpgp_is_a_non_goal(self):
        plan = (ROOT / "docs/ios-client-plan.md").read_text(encoding="utf-8")
        # M5 must now name the chosen 5/5 solution and its scaffold status
        self.assertIn("rpgp + UniFFI, 5/5", plan)
        self.assertIn("rust/offlineotp-openpgp", plan)
        self.assertIn("rpgp 0.21 + UniFFI", plan)
        # Non-goals must not still list OpenPGP as \"needs an implementation\" with the
        # old incompatible-stack rationale; it now describes the unified backend and the
        # remaining steps (per-ABI packaging, removal of PGPainless).
        self.assertNotIn(
            "OpenPGP (`.json.gpg`) backups — they need an OpenPGP implementation, and the existing clients use\nincompatible ones",
            plan
        )
        self.assertIn("## Non-goals for v1", plan)
        # The gap-closing rationale must appear: Android external vs desktop embedded vs iOS none,
        # now closed by one implementation for all five distributions.
        self.assertIn("Android via external OpenKeychain provider, desktop via embedded PGPainless, iOS with", plan)
        self.assertIn("none). That gap is now closed", plan)
        self.assertIn("one implementation for all five distributions", plan)

    def test_parity_doc_closed_the_openpgp_gap(self):
        parity = (ROOT / "docs/client-parity-2026-11-02.md").read_text(encoding="utf-8")
        self.assertIn("Two things are deliberately *not* the same", parity)
        self.assertNotIn("Three things are deliberately *not* the same", parity)
        self.assertIn("rust/offlineotp-openpgp", parity)
        self.assertIn("rpgp 0.21 + UniFFI", parity)
        self.assertIn("one implementation for all five distributions", parity)
        # The previous outstanding line must be gone
        self.assertNotIn(
            "OpenPGP backups (`.json.gpg`) are still outstanding,\nbecause they need a whole OpenPGP implementation.",
            parity
        )

    def test_changelog_records_the_scaffold_and_its_remaining_steps(self):
        changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
        self.assertIn("OpenPGP: Scaffold the unified Rust backend", changelog)
        self.assertIn("rust/offlineotp-openpgp", changelog)
        self.assertIn("rpgp 0.21 + UniFFI", changelog)
        self.assertIn("five distributions (Android, Debian, Windows, macOS, iOS)", changelog)
        self.assertIn("per-abi packaging", changelog.lower())
        self.assertIn("cargo test", changelog.lower())

    def test_readme_names_the_unified_backend_and_its_deprecation_plan(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn("rust/offlineotp-openpgp", readme)
        self.assertIn("rpgp 0.21 + UniFFI, 5/5", readme)
        # The pin comment must explain that PGPainless is still present but will be removed
        self.assertIn("org.pgpainless:pgpainless-core:2.0.4", readme)
        self.assertIn("will replace it once the per-abi", readme.lower())


if __name__ == "__main__":
    unittest.main()

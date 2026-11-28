"""Chunk-2 wiring guards; Swift/native execution is a separate required gate."""
from pathlib import Path
import json
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]


class SharedEntryMetadataTest(unittest.TestCase):
    def test_domain_is_a_native_target_and_exported_to_swift_without_a_jvm_engine(self):
        build = (ROOT / "shared-domain/build.gradle").read_text()
        for target in ("iosArm64()", "iosSimulatorArm64()", "androidTarget", "jvm('desktop')"):
            self.assertIn(target, build)
        self.assertNotIn("project(", build)
        self.assertIn("api project(':shared-domain')", (ROOT / "core/build.gradle").read_text())
        foundation = (ROOT / "ui-foundation/build.gradle").read_text()
        self.assertIn("export rootProject.findProject(':shared-domain')", foundation)
        self.assertIn("api project(':shared-domain')", foundation)

    def test_both_adapters_use_shared_serialization_without_rebuilding_unknown_fields(self):
        kotlin = (ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp/Database/EntryJson.kt").read_text()
        self.assertIn("import com.fairuniversal.offlineotp.domain.EntryMetadata", kotlin)
        self.assertIn("EntryMetadataError.TEXT_TOO_LONG", kotlin)
        self.assertIn("detailsVisible).toJson()", kotlin)
        swift = (ROOT / "ios/App/Sources/SharedEntryMetadataAdapter.swift").read_text()
        self.assertIn("EntryMetadata(issuer:", swift)
        self.assertIn("metadata.validationError() == nil", swift)
        self.assertIn("JSONValue.decode(metadata.toJson())", swift)
        self.assertIn("var fields = entry.fields", swift)
        self.assertIn("for (name, value) in patch", swift)
        host = (ROOT / "ios/App/Sources/ComposeVaultHost.swift").read_text()
        self.assertEqual(2, host.count("built = try SharedEntryMetadataAdapter.roundTrip(built)"))

    def test_cross_build_corpus_includes_large_numbers_unicode_duplicates_and_extensions(self):
        fixture = ROOT / "desktop/src/test/resources/golden/portable-entry-metadata.json"
        rows = json.loads(fixture.read_text())["cases"]
        self.assertEqual(2, len(rows))
        first, second = [json.loads(row["document"])[0] for row in rows]
        self.assertGreater(first["last_used"], 2**53)
        self.assertEqual(2**63 - 1, first["used_frequency"])
        self.assertEqual(-(2**63), second["last_used"])
        self.assertGreater(second["counter"], 2**53)
        self.assertEqual(first["tags"][0], first["tags"][-1])
        self.assertIn("😀", first["issuer"])
        for entry in (first, second):
            self.assertIn("extension_object", entry)
            self.assertIn("extension_list", entry)
            self.assertIsNone(entry["extension_null"])

    def test_simulator_adapter_test_uses_a_bundled_corpus_not_host_filesystem_access(self):
        project = (ROOT / "ios/project.yml").read_text().split("  OfflineOTPAppTests:", 1)[1]
        self.assertIn("../desktop/src/test/resources/golden/portable-entry-metadata.json", project)
        self.assertIn("buildPhase: resources", project)
        test = (ROOT / "ios/App/Tests/SharedEntryMetadataAdapterTests.swift").read_text()
        self.assertIn('Bundle(for: Self.self).url(forResource: "portable-entry-metadata"', test)
        self.assertNotIn("#filePath", test)

    def test_sandbox_compiles_the_real_domain_without_platform_stubs(self):
        ui = (ROOT / "scripts/kotlin-sandbox/compile-ui.sh").read_text()
        self.assertIn('-module-name shared-domain "${DOMAIN_SOURCES[@]}" -d "$OUT/domain"', ui)
        for name in ("compile-core.sh", "compile-desktop.sh", "compile-ui.sh", "run.sh"):
            self.assertIn("shared-domain/src/commonMain/kotlin", (ROOT / "scripts/kotlin-sandbox" / name).read_text())


class SharedEntryDocumentWiringTest(unittest.TestCase):
    def test_all_kind_corpus_is_reproducible_and_complete(self):
        subprocess.run([sys.executable, str(ROOT / "scripts/fixtures/generate-entry-documents.py"), "--check"], check=True)
        corpus = json.loads((ROOT / "desktop/src/test/resources/golden/portable-entry-documents.json").read_text())
        self.assertEqual({"TOTP", "HOTP", "MOTP", "STEAM", "NOTE", "SSH_KEY", "PASSWORD", "PASSWORD_ARCHIVE", "EMAIL"},
                         {row["kind"] for row in corpus["valid"]})
        self.assertGreaterEqual(len(corpus["invalid"]), 8)

    def test_jvm_vault_io_and_aliases_use_the_portable_contract(self):
        root = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp"
        codec = (root / "Database/VaultJson.kt").read_text()
        self.assertIn("EntryDocumentCodec().decode(data)", codec)
        self.assertIn("EntryDocumentCodec().roundTrip(document)", codec)
        self.assertIn("record.materialJson()", codec)
        self.assertIn("entry.setPortableExtensions(record.extensionFieldsJson())", codec)
        enums = (root / "Utilities/OtpParseResult.kt").read_text()
        self.assertIn("typealias OtpKind = com.fairuniversal.offlineotp.domain.EntryRecordKind", enums)
        self.assertIn("typealias OtpHashAlgorithm = com.fairuniversal.offlineotp.domain.EntryRecordAlgorithm", enums)

    def test_production_apple_vault_and_all_app_documents_use_the_shared_adapter(self):
        source = ROOT / "ios/App/Sources"
        store = (source / "VaultStore.swift").read_text()
        self.assertIn("let documents = SharedEntryDocumentAdapter()", store)
        self.assertIn("settings: settings, documents: documents, protocolPolicy: protocolPolicy, persistence: SharedPersistenceAdapter())", store)
        self.assertEqual(2, store.count("andOtpVariant: andOtpVariant, documents: entryDocuments"))
        self.assertNotIn("VaultDocument.decode(", store)
        self.assertNotIn("VaultDocument.encode(", store)
        host = (source / "ComposeVaultHost.swift").read_text()
        self.assertNotIn("VaultDocument.encode(", host)
        adapter = (source / "SharedEntryDocumentAdapter.swift").read_text()
        self.assertIn("EntryDocumentCodec().decode(text: text)", adapter)
        self.assertIn("record.materialJson()", adapter)
        self.assertIn("entry.attachValidatedExtensionFields(record.extensionFieldsJson())", adapter)
        self.assertIn("_ = try decode(text)", adapter)
        vault = (ROOT / "ios/Sources/OfflineOTPKit/PasswordVault.swift").read_text()
        self.assertIn("documents.decode(text)", vault)
        self.assertIn("documents.encode(entries)", vault)
        self.assertNotIn("VaultDocument.decode(", vault)
        self.assertNotIn("VaultDocument.encode(", vault)

    def test_unknown_numbers_never_need_a_native_number_to_be_saved(self):
        common = ROOT / "shared-domain/src/commonMain/kotlin/com/fairuniversal/offlineotp/domain"
        for path in common.glob("*.kt"):
            self.assertNotIn(".toDouble(", path.read_text())
        entry = (ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp/Database/Entry.kt").read_text()
        self.assertIn("fun detachedCopy(): Entry = Entry(nativeJSON())", entry)
        self.assertIn("raw.getValue(it)", entry)
        swift = (ROOT / "ios/Sources/OfflineOTPKit/Entry.swift").read_text()
        self.assertIn("portableExtensions[name] ?? fields[name]?.compact()", swift)
        self.assertIn("portableExtensions = source.portableExtensions", swift)

    def test_new_kind_corpus_is_bundled_for_actual_apple_adapter_tests(self):
        project = (ROOT / "ios/project.yml").read_text().split("  OfflineOTPAppTests:", 1)[1]
        self.assertIn("../desktop/src/test/resources/golden/portable-entry-documents.json", project)
        tests = (ROOT / "ios/App/Tests/SharedEntryDocumentAdapterTests.swift").read_text()
        self.assertIn('Bundle(for: Self.self).url(forResource: "portable-entry-documents"', tests)
        self.assertIn("store.restore(encrypted:", tests)
        self.assertIn("reopened.unlock(password:", tests)
        self.assertIn("testFutureNumbersSurviveNativeMetadataEdits", tests)

    def test_opaque_fields_participate_in_edit_and_hardware_staleness_checks(self):
        host = (ROOT / "ios/App/Sources/ComposeVaultHost.swift").read_text()
        self.assertIn("private var openedFields: String?", host)
        self.assertIn("store.entries[index].compact() == openedFields", host)
        self.assertIn("store.entries[index].compact() == hardwareCopyFields", host)
        self.assertIn("replacement.preserveExtensionFields(from: entry)", host)
        fingerprint = (ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control/DesktopEntryTransaction.kt").read_text()
        self.assertIn("VaultJson.entriesToString(listOf(entry))", fingerprint)
        self.assertNotIn("entry.toJSON().toString()", fingerprint)

    def test_swift_compatibility_projection_and_shared_schema_agree_on_known_names(self):
        import re
        swift = (ROOT / "ios/Sources/OfflineOTPKit/Entry.swift").read_text()
        domain = (ROOT / "shared-domain/src/commonMain/kotlin/com/fairuniversal/offlineotp/domain/EntryRecord.kt").read_text()
        swift_names = set(re.findall(r'"([a-z_]+)"', swift.split("static let canonicalFieldOrder = [", 1)[1].split("]", 1)[0]))
        shared_names = set(re.findall(r'"([a-z_]+)"', domain.split("private val KNOWN_FIELDS = setOf(", 1)[1].split(")", 1)[0]))
        self.assertEqual(swift_names, shared_names)

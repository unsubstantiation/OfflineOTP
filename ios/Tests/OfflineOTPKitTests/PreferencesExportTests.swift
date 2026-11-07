import Foundation
import XCTest
@testable import OfflineOTPKit

/// Mirrors `PreferencesExportTest.kt`: typed round-trip, the platform rule, and the refusals.
final class PreferencesExportTests: XCTestCase {

    final class FakePort: PreferencesExport.SettingsPort {
        var values: [String: PreferencesExport.Value] = [:]
        var extra: Set<String> = []
        var writes: [String: PreferencesExport.Value] = [:]

        init(_ seeded: [String: PreferencesExport.Value] = [:]) { values = seeded }

        func storedKeys() -> Set<String> { Set(values.keys).union(extra) }
        func read(_ key: String) -> PreferencesExport.Value? { values[key] }
        func write(_ key: String, _ value: PreferencesExport.Value) { writes[key] = value }
    }

    func testRoundTripKeepsTypesAndWritesOnlyWhatWasStored() throws {
        let source = FakePort([PreferencesExport.inactivityLockKey: .integer(120)])
        let export = try PreferencesExport.export(source)
        XCTAssertEqual(1, export.keysWritten)
        XCTAssertFalse(export.includesSecrets)

        let target = FakePort()
        let result = try PreferencesExport.apply(export.bytes, into: target)
        XCTAssertEqual(1, result.applied)
        XCTAssertEqual([PreferencesExport.inactivityLockKey: .integer(120)], target.writes)
    }

    func testAbsentSettingIsNotWrittenAsItsDefault() throws {
        let export = try PreferencesExport.export(FakePort())
        XCTAssertEqual(0, export.keysWritten)
        let target = FakePort()
        _ = try PreferencesExport.apply(export.bytes, into: target)
        XCTAssertTrue(target.writes.isEmpty)
    }

    func testAFileForAnotherClientIsRefusedAndNothingIsWritten() throws {
        let source = FakePort([PreferencesExport.inactivityLockKey: .integer(60)])
        let androidFile = try PreferencesExport.export(source, platform: PreferencesExport.platformAndroid)
        let target = FakePort()
        XCTAssertThrowsError(try PreferencesExport.apply(androidFile.bytes, into: target)) { error in
            XCTAssertEqual(error as? PreferencesExport.PreferencesError,
                           .wrongPlatform(source: PreferencesExport.platformAndroid,
                                          target: PreferencesExport.platformIos))
        }
        XCTAssertTrue(target.writes.isEmpty)

        let own = try PreferencesExport.export(source)
        let text = String(decoding: own.bytes, as: UTF8.self)
        XCTAssertTrue(text.contains("\"platform\":\"\(PreferencesExport.platformIos)\""), text)
    }

    func testVaultMaterialIsNeverExportedOrNamed() throws {
        let source = FakePort([PreferencesExport.inactivityLockKey: .integer(30)])
        source.extra = [VaultSettingsKeys.authSalt, VaultSettingsKeys.authCredentials,
                        PreferencesExport.quickUnlockKey]
        let export = try PreferencesExport.export(source)
        let text = String(decoding: export.bytes, as: UTF8.self)
        for key in [VaultSettingsKeys.authSalt, VaultSettingsKeys.authCredentials,
                    PreferencesExport.quickUnlockKey] {
            XCTAssertFalse(text.contains(key), "\(key) leaked: \(text)")
            XCTAssertFalse(export.skipped.contains(key))
            XCTAssertTrue(PreferencesExport.isDeviceBound(key))
        }
        // A key this build simply does not know *is* reported, so a newer file is not a mystery.
        let future = FakePort([:])
        future.extra = ["ui_from_the_future"]
        XCTAssertEqual(["ui_from_the_future"], try PreferencesExport.export(future).skipped)
    }

    func testUnknownKeysAreSkippedAndBadTypesRejectTheWholeDocument() throws {
        let source = FakePort([PreferencesExport.inactivityLockKey: .integer(30)])
        let export = try PreferencesExport.export(source)
        var document = try XCTUnwrap(try JSONSerialization.jsonObject(with: export.bytes) as? [String: Any])

        var withUnknown = document
        var settings = try XCTUnwrap(withUnknown["settings"] as? [String: Any])
        settings["ui_from_the_future"] = 1
        withUnknown["settings"] = settings
        let unknownBytes = try JSONSerialization.data(withJSONObject: withUnknown, options: [.sortedKeys])
        let target = FakePort()
        let result = try PreferencesExport.apply(unknownBytes, into: target)
        XCTAssertEqual(["ui_from_the_future"], result.unknownKeys)
        XCTAssertEqual(1, result.applied)

        // A wrong type for a known key rejects the document before anything is written.
        document["settings"] = [PreferencesExport.inactivityLockKey: "not an integer"]
        let badBytes = try JSONSerialization.data(withJSONObject: document, options: [.sortedKeys])
        let untouched = FakePort()
        XCTAssertThrowsError(try PreferencesExport.apply(badBytes, into: untouched))
        XCTAssertTrue(untouched.writes.isEmpty)

        // Garbage, an empty file and a foreign format are all refused.
        for bad in [Data(), Data("not json".utf8),
                    try JSONSerialization.data(withJSONObject: ["format": "something.else", "version": 1,
                                                               "platform": PreferencesExport.platformIos,
                                                               "settings": [:]])] {
            XCTAssertThrowsError(try PreferencesExport.apply(bad, into: FakePort()))
        }
    }

    func testADeviceBoundKeyInTheFileIsRefusedNotApplied() throws {
        // A hand-edited file can name anything; a vault key must be refused even when it is in the
        // catalog-less `settings` map, and the neighbouring portable value still applies.
        let document: [String: Any] = [
            "format": PreferencesExport.format,
            "version": PreferencesExport.version,
            "platform": PreferencesExport.platformIos,
            "includesSecrets": false,
            "settings": [PreferencesExport.inactivityLockKey: 45,
                         VaultSettingsKeys.authSalt: "made-up-salt"],
        ]
        let bytes = try JSONSerialization.data(withJSONObject: document, options: [.sortedKeys])
        let target = FakePort()
        let result = try PreferencesExport.apply(bytes, into: target)
        XCTAssertEqual(1, result.applied)
        XCTAssertEqual([VaultSettingsKeys.authSalt], result.refusedKeys)
        XCTAssertNil(target.writes[VaultSettingsKeys.authSalt])
    }

    func testASecretValueNeverDescribesItsContents() {
        let secret = PreferencesExport.Value.text("hunter2")
        XCTAssertFalse(secret.description.contains("hunter2"))
        XCTAssertTrue(secret.description.contains("chars"))
    }
}

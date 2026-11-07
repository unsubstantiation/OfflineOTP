import Foundation
import XCTest
@testable import OfflineOTPKit

/// Mirrors `PasswordCsvExportTest.kt`: the same dialect rules, the same refusals, the same edit.
final class PasswordCsvExportTests: XCTestCase {
    private let chrome = Data(("name,url,username,password,note\r\n"
        + "Site,https://site.test,alice,s3cret,first note\r\n").utf8)
    private let safari = Data(("Title,URL,Username,Password,Notes,OTPAuth\r\n"
        + "Site,https://site.test,alice,\"p,\"\"q\"\"\",note,otpauth://example\r\n").utf8)

    private func imported(_ data: Data) throws -> [Entry] {
        try PasswordImport.entries(filename: "passwords.csv", data: data, mode: .individual)
    }

    func testEachDialectKeepsItsOwnHeaderAndColumns() throws {
        let chromeEntries = try imported(chrome)
        XCTAssertEqual(PasswordCsvExport.support(chromeEntries[0]), .exportable(.chrome))
        let exported = try PasswordCsvExport.exportCsv(entries: chromeEntries, dialect: .chrome)
        let text = String(decoding: exported, as: UTF8.self)
        XCTAssertTrue(text.hasPrefix("name,url,username,password,note\r\n"), text)
        XCTAssertTrue(text.contains("s3cret"), text)
        XCTAssertTrue(text.contains("first note"), text)

        let safariEntries = try imported(safari)
        XCTAssertEqual(PasswordCsvExport.support(safariEntries[0]), .exportable(.safari))
        let safariText = String(decoding: try PasswordCsvExport.exportCsv(entries: safariEntries, dialect: .safari),
                                as: UTF8.self)
        XCTAssertTrue(safariText.hasPrefix("Title,URL,Username,Password,Notes,OTPAuth\r\n"), safariText)
        XCTAssertTrue(safariText.contains("otpauth://example"), safariText)
        XCTAssertTrue(safariText.contains("\"p,\"\"q\"\"\""), safariText)
    }

    func testMixedHeadersUseTheCanonicalHeader() throws {
        let entries = try imported(chrome) + imported(safari)
        let mixed = try PasswordCsvExport.exportCsv(entries: entries, dialect: .firefox)
        let text = String(decoding: mixed, as: UTF8.self)
        let header = text.components(separatedBy: "\r\n").first ?? ""
        XCTAssertEqual(header, PasswordCsvExport.canonicalHeader(.firefox).joined(separator: ","))
        XCTAssertEqual(PasswordCsvExport.agreedDialect(entries), .firefox)
        XCTAssertEqual(PasswordCsvExport.agreedDialect(try imported(chrome)), .chrome)
    }

    func testForeignAndNonPasswordEntriesAreRefused() throws {
        let session = try OfflineOTPKit.Entry.passwordFields(
            label: "alice@host", issuer: "host",
            fields: [
                PasswordMaterial.Field(name: "url", value: "ssh://host:22"),
                PasswordMaterial.Field(name: "username", value: "alice"),
                PasswordMaterial.Field(name: "password", value: "pw"),
                PasswordMaterial.Field(name: LinScpVault.sessionJsonColumn, value: "{}"),
            ])
        let archive = try OfflineOTPKit.Entry.passwordArchive(filename: "browser.csv", data: chrome)
        XCTAssertEqual(PasswordCsvExport.support(session), .foreign)
        XCTAssertEqual(PasswordCsvExport.support(archive), .notPassword)
        XCTAssertThrowsError(try PasswordCsvExport.exportCsv(entries: [session], dialect: .firefox)) { error in
            XCTAssertEqual(error as? PasswordCsvExportError, .notABrowserEntry)
        }
        XCTAssertThrowsError(try PasswordCsvExport.exportCsv(entries: [], dialect: .firefox)) { error in
            XCTAssertEqual(error as? PasswordCsvExportError, .nothingToExport)
        }
    }

    func testEditingChangesOnlyTheRequestedFields() throws {
        let entry = try imported(chrome)[0]
        let edited = try PasswordCsvExport.withEditedFields(entry, username: "carol", password: "new pw")
        let fields = try edited.passwordFields()
        XCTAssertEqual(fields.value("username"), "carol")
        XCTAssertEqual(fields.value("password"), "new pw")
        XCTAssertEqual(fields.value("url"), "https://site.test")
        XCTAssertEqual(fields.value("note"), "first note")
        XCTAssertEqual(edited.label, "carol")
        XCTAssertEqual(edited.issuer, "Site")

        // A Safari entry keeps its Notes and OTPAuth after an edit and still exports as Safari.
        let safariEntry = try imported(safari)[0]
        let moved = try PasswordCsvExport.withEditedFields(safariEntry, url: "https://moved.test")
        XCTAssertEqual(try moved.passwordFields().value("otpauth"), "otpauth://example")
        XCTAssertEqual(PasswordCsvExport.support(moved), .exportable(.safari))
        let text = String(decoding: try PasswordCsvExport.exportCsv(entries: [moved], dialect: .safari), as: UTF8.self)
        XCTAssertTrue(text.contains("https://moved.test"), text)
        XCTAssertFalse(text.contains("https://site.test"), text)
    }

    func testEmptyPasswordIsRefusedAndTheEntryIsUnchanged() throws {
        let entry = try imported(chrome)[0]
        XCTAssertThrowsError(try PasswordCsvExport.withEditedFields(entry, password: "")) { error in
            XCTAssertEqual(error as? PasswordCsvExportError, .emptyPassword)
        }
        XCTAssertEqual(try entry.passwordFields().value("password"), "s3cret")
    }
}

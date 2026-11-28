package com.fairuniversal.offlineotp.domain

/** ObjC-friendly closed replies; payloads can carry credentials and never render in diagnostics. */
class ProvisioningReply(val value: PortableOtp?, val error: String?) { override fun toString() = "ProvisioningReply(redacted)" }
class MigrationReply(val value: PortableMigrationPayload?, val error: String?) { override fun toString() = "MigrationReply(redacted)" }
class CsvRecord(val fields: List<String>) { override fun toString() = "CsvRecord(redacted)" }
class CsvReply(val columns: List<String>, val rows: List<CsvRecord>, val error: String?) {
    override fun toString() = "CsvReply(redacted)"
}
class InterchangeCodec {
    fun otp(input: String): ProvisioningReply = when (val result = ProvisioningCodec.parse(input)) {
        is ProvisioningResult.Success -> ProvisioningReply(result.value, null)
        is ProvisioningResult.Failure -> ProvisioningReply(null, result.error.name)
    }
    fun migration(input: String): MigrationReply = when (val result = MigrationInterchangeCodec.parse(input)) {
        is PortableMigrationResult.Success -> MigrationReply(result.payload, null)
        is PortableMigrationResult.Failure -> MigrationReply(null, result.error.name)
    }
    fun csv(bytes: ByteArray, thunderbird: Boolean): CsvReply {
        if (thunderbird) return when (val result = ThunderbirdCsvCodec.parse(bytes)) {
            is ThunderbirdCsvCodec.Result.Success -> CsvReply(result.columns, result.rows.map { row -> CsvRecord((0 until row.size).map { row.valueAt(it) }) }, null)
            is ThunderbirdCsvCodec.Result.Failure -> CsvReply(emptyList(), emptyList(), result.error.name)
        }
        return when (val result = BrowserCsvCodec.parse(bytes)) {
            is BrowserCsvCodec.Result.Success -> CsvReply(result.columns, result.rows.map { row -> CsvRecord((0 until row.size).map { row.valueAt(it) }) }, null)
            is BrowserCsvCodec.Result.Failure -> CsvReply(emptyList(), emptyList(), result.error.name)
        }
    }
    fun exportCsv(columns: List<String>, rows: List<CsvRecord>): ByteArray? =
        try { ThunderbirdCsvCodec.exportCsv(columns, rows.map { it.fields }) } catch (_: Exception) { null }
}

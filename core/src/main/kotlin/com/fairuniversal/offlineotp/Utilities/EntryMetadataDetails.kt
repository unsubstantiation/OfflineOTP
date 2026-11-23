package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.Database.Entry

/**
 * Formats read-only, non-secret metadata lines for OTP and SSH-key entries across Android and
 * Desktop (matching iOS `detailsSurface`).
 *
 * Never discloses OTP secrets, mOTP PINs, hidden passwords, SSH private keys, or SSH connection
 * passwords.
 */
object EntryMetadataDetails {

    private val otpTypes = setOf(
        Entry.OTPType.TOTP,
        Entry.OTPType.HOTP,
        Entry.OTPType.STEAM,
        Entry.OTPType.MOTP
    )

    @JvmStatic
    fun supports(entry: Entry): Boolean =
        entry.isSshKey() || entry.getType() in otpTypes

    @JvmStatic
    fun format(entry: Entry): String? {
        if (!supports(entry)) return null
        val lines = ArrayList<String>()
        if (entry.isSshKey()) {
            lines.add("Type: Keys")
            val issuer = entry.getIssuer().trim()
            if (issuer.isNotEmpty()) lines.add("Issuer: $issuer")
            val label = entry.getLabel().trim()
            if (label.isNotEmpty()) lines.add("Account: $label")
            val keys = entry.getSshKeys()
            val publicKey = keys.publicKey.trim()
            val keyType = publicKey.substringBefore(' ').trim()
            if (keyType.startsWith("ssh-") || keyType.startsWith("ecdsa-") || keyType.startsWith("sk-")) {
                lines.add("Key type: $keyType")
            }
            val conn = entry.getSshConnection()
            if (conn != null) {
                if (conn.host.isNotBlank()) lines.add("Host: ${conn.host}")
                if (conn.port > 0) lines.add("Port: ${conn.port}")
                if (conn.username.isNotBlank()) lines.add("Username: ${conn.username}")
            }
            if (publicKey.isNotEmpty()) lines.add("Public key: $publicKey")
            if (keys.privateKey.isNotBlank()) lines.add("Private key: Stored")
        } else {
            val type = entry.getType()
            lines.add("Type: ${type.name}")
            val issuer = entry.getIssuer().trim()
            if (issuer.isNotEmpty()) lines.add("Issuer: $issuer")
            val label = entry.getLabel().trim()
            if (label.isNotEmpty()) lines.add("Account: $label")
            if (type == Entry.OTPType.TOTP || type == Entry.OTPType.HOTP) {
                lines.add("Algorithm: ${entry.getAlgorithm().name}")
            }
            lines.add("Digits: ${entry.getDigits()}")
            if (entry.isCounterBased()) {
                lines.add("Counter: ${entry.getCounter()}")
            } else {
                val period = if (type == Entry.OTPType.MOTP) 10 else entry.getPeriod()
                lines.add("Period: ${period}s")
            }
        }
        val tags = entry.getTags().filter { it.isNotBlank() }
        if (tags.isNotEmpty()) {
            lines.add("Tags: ${tags.joinToString(", ")}")
        }
        return lines.joinToString("\n").takeIf { it.isNotBlank() }
    }
}

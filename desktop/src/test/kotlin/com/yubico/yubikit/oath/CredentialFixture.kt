package com.yubico.yubikit.oath

/** Test-only access to YubiKit 3.3.0's package-private constructor; never opens a device.
 * Keep this in the SDK package so real Gradle tests compile against the actual API.
 * Issuer/account/period are parsed by YubiKit from the ID, not invented by a fake.
 */
fun credentialFixture(id: String, type: OathType = OathType.TOTP): Credential =
    Credential("test-device", id.toByteArray(Charsets.UTF_8), type, false)

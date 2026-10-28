/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless vectors for the desktop security-key workflows. Real PC/SC hardware cannot exist in
 * this environment, so the same scripted yubikit stubs the core sandbox checks use stand in for
 * the key: OathSandbox for OATH and FidoSandbox for the FIDO2 PRF. The envelope store is the
 * real file-backed DesktopHardwareUnlockStore over a temporary directory.
 */
package org.shadowice.flocke.andotp.desktop.hardware

import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.fido.client.FidoSandbox
import com.yubico.yubikit.fido.webauthn.Extensions
import com.yubico.yubikit.fido.webauthn.PublicKeyCredential
import com.yubico.yubikit.oath.Code
import com.yubico.yubikit.oath.Credential
import com.yubico.yubikit.oath.OathSandbox
import com.yubico.yubikit.oath.OathType
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.shadowice.flocke.andotp.Utilities.HardwareUnlockStoragePort
import org.shadowice.flocke.andotp.desktop.control.HardwareKeyManager
import org.shadowice.flocke.andotp.desktop.control.VaultController
import org.shadowice.flocke.andotp.desktop.storage.DesktopHardwareUnlockStore
import org.shadowice.flocke.andotp.desktop.storage.DesktopStorage
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator

class HardwareKeyManagerTest {

    private class FakeSource(var devices: List<YubiKeyDevice>) : HardwareDeviceSource {
        var closed = false
            private set
        override fun scan(): List<YubiKeyDevice> = devices
        override fun close() { closed = true }
    }

    /** A key whose CCID connection is accepted by the stubbed OathSession. */
    private fun smartCardDevice(): YubiKeyDevice = object : YubiKeyDevice {
        override fun <T> openConnection(type: Class<T>): T = type.cast(
            object : SmartCardConnection { override fun close() {} })!!
    }

    private fun failingDevice(): YubiKeyDevice = object : YubiKeyDevice {
        override fun <T> openConnection(type: Class<T>): T =
            throw RuntimeException("detail that must never reach the caller")
    }

    private fun manager(source: HardwareDeviceSource, dir: File): HardwareKeyManager =
        HardwareKeyManager(source, DesktopHardwareUnlockStore(dir))

    private fun managerWithStore(source: HardwareDeviceSource, store: HardwareUnlockStoragePort) =
        HardwareKeyManager(source, store)

    /** Re-arms the PRF assertion after enrollment: Enrollment.close() wipes the shared stub
     *  array, while a real key evaluates the PRF afresh on every calculate(). The producer builds
     *  a fresh value array rather than reusing the zeroed enrollment instance. */
    private fun rearmAssertion(id: ByteArray, prfValues: () -> ByteArray) {
        FidoSandbox.assertion = PublicKeyCredential(
            id.clone(),
            Extensions(mapOf("prf" to mapOf("results" to mapOf("first" to prfValues())))))
    }

    private class RejectingStore : HardwareUnlockStoragePort {
        var writes = 0
            private set
        override fun readEnvelope(): ByteArray? = null
        override fun writeEnvelope(envelope: ByteArray): Boolean {
            writes++
            return false
        }
        override fun removeEnvelope() {}
    }

    @After
    fun resetSandboxes() {
        OathSandbox.reset()
        FidoSandbox.reset()
    }

    @Test
    fun missingKeyCancellationAndProviderFailureAreTypedResults() {
        val dir = Files.createTempDirectory("offlineotp-hw-").toFile()
        val empty = manager(FakeSource(emptyList()), dir)

        assertTrue(empty.listOathAccounts(null, HardwareKeyManager.CancelToken())
            is HardwareKeyManager.Result.NoKey)
        assertTrue(empty.unlockWithKey(null, HardwareKeyManager.CancelToken())
            is HardwareKeyManager.Result.Failed) // no enrollment yet

        val token = HardwareKeyManager.CancelToken().also { it.cancel() }
        assertTrue(empty.listOathAccounts(null, token) is HardwareKeyManager.Result.Canceled)

        val broken = manager(FakeSource(listOf(failingDevice())), dir)
        assertTrue(broken.listOathAccounts(null, HardwareKeyManager.CancelToken())
            is HardwareKeyManager.Result.Failed)
    }

    @Test
    fun oathListAndCalculateRunThroughTheFakeKey() {
        val dir = Files.createTempDirectory("offlineotp-hw-").toFile()
        val credential = Credential(byteArrayOf(1, 2), OathType.TOTP, "Example", "alice")
        OathSandbox.codes = mapOf(credential to Code("123456", 0L, Long.MAX_VALUE))
        val manager = manager(FakeSource(listOf(smartCardDevice())), dir)

        val listed = manager.listOathAccounts(null, HardwareKeyManager.CancelToken())
        assertTrue(listed is HardwareKeyManager.Result.Ok)
        assertEquals(1, (listed as HardwareKeyManager.Result.Ok).value.items.size)

        val calculated = manager.calculateOath(
            null, "fake-device",
            org.shadowice.flocke.andotp.Utilities.HardwareOathClient.Account(
                credential, null, 0),
            HardwareKeyManager.CancelToken())
        assertTrue(calculated is HardwareKeyManager.Result.Ok)
        assertEquals("123456", (calculated as HardwareKeyManager.Result.Ok).value.code)
        assertEquals(1, OathSandbox.calculations)
    }

    @Test
    fun enrollmentPersistsAnEnvelopeAndHardwareUnlockRecoversTheVaultKey() {
        val dir = Files.createTempDirectory("offlineotp-hw-").toFile()
        val vaultKey = KeyGenerator.getInstance("AES").generateKey()
        val id = byteArrayOf(3, 1, 4, 1, 5)
        val prf = ByteArray(32) { (it + 1).toByte() }
        FidoSandbox.enrollment = PublicKeyCredential(
            id, Extensions(mapOf("prf" to mapOf("enabled" to true))))
        FidoSandbox.assertion = PublicKeyCredential(
            id.clone(),
            Extensions(mapOf("prf" to mapOf("results" to mapOf("first" to prf)))))

        val manager = manager(FakeSource(listOf(smartCardDevice())), dir)
        assertTrue(manager.enroll(null, vaultKey, HardwareKeyManager.CancelToken())
            is HardwareKeyManager.Result.Ok)
        assertTrue(manager.isEnrolled())

        rearmAssertion(id) { ByteArray(32) { (it + 1).toByte() } }
        val recovered = manager.unlockWithKey(null, HardwareKeyManager.CancelToken())
        assertTrue(recovered is HardwareKeyManager.Result.Ok)
        assertArrayEquals(vaultKey.encoded, (recovered as HardwareKeyManager.Result.Ok).value.encoded)

        assertTrue(manager.removeEnrollment())
        assertTrue(!manager.isEnrolled())
    }

    @Test
    fun failedEnrollmentPersistsNothingAndCanceledEnrollmentDoesNotTouchTheStore() {
        val dir = Files.createTempDirectory("offlineotp-hw-").toFile()
        val vaultKey = KeyGenerator.getInstance("AES").generateKey()
        val id = byteArrayOf(9)
        FidoSandbox.enrollment = PublicKeyCredential(
            id, Extensions(mapOf("prf" to mapOf("enabled" to true))))
        FidoSandbox.assertion = PublicKeyCredential(
            id, Extensions(mapOf("prf" to mapOf("results" to mapOf("first" to ByteArray(32) { 2 })))))

        // The stubbed WebAuthnClient never opens the device, so a rejecting store is the reliable
        // way to exercise the persistence-failure branch.
        val rejecting = RejectingStore()
        val failed = managerWithStore(FakeSource(listOf(smartCardDevice())), rejecting)
        assertTrue(failed.enroll(null, vaultKey, HardwareKeyManager.CancelToken())
            is HardwareKeyManager.Result.Failed)
        assertEquals(1, rejecting.writes)
        assertTrue(rejecting.readEnvelope() == null)

        val callsBeforeCancel = FidoSandbox.makeCalls
        val token = HardwareKeyManager.CancelToken().also { it.cancel() }
        val canceled = manager(FakeSource(listOf(smartCardDevice())), dir)
        assertTrue(canceled.enroll(null, vaultKey, token) is HardwareKeyManager.Result.Canceled)
        assertTrue(!canceled.isEnrolled())
        // A pre-canceled token must not reach the device at all.
        assertEquals(callsBeforeCancel, FidoSandbox.makeCalls)
    }

    @Test
    fun recoveredKeyOpensTheVaultThroughTheController() {
        val dir = Files.createTempDirectory("offlineotp-hw-").toFile()
        val password = "hardware-controller-password"
        val controller = VaultController(DesktopStorage.open(dir))
        val opened = controller.createVault(password) as VaultController.Session.Loaded

        val id = byteArrayOf(7, 7, 7)
        val prf = ByteArray(32) { (it + 4).toByte() }
        FidoSandbox.enrollment = PublicKeyCredential(
            id, Extensions(mapOf("prf" to mapOf("enabled" to true))))
        FidoSandbox.assertion = PublicKeyCredential(
            id.clone(),
            Extensions(mapOf("prf" to mapOf("results" to mapOf("first" to prf)))))

        val manager = manager(FakeSource(listOf(smartCardDevice())), dir)
        assertTrue(manager.enroll(null, opened.key, HardwareKeyManager.CancelToken())
            is HardwareKeyManager.Result.Ok)
        rearmAssertion(id) { ByteArray(32) { (it + 4).toByte() } }
        val recovered = (manager.unlockWithKey(null, HardwareKeyManager.CancelToken())
            as HardwareKeyManager.Result.Ok).value

        val session = controller.unlockWithKey(recovered)
        assertTrue(session is VaultController.Session.Loaded)
        assertEquals(0, (session as VaultController.Session.Loaded).entries.size)
    }

    @Test
    fun pinIsZeroizedOnEveryOperationPath() {
        val dir = Files.createTempDirectory("offlineotp-hw-").toFile()
        OathSandbox.codes = emptyMap()
        val ready = manager(FakeSource(listOf(smartCardDevice())), dir)
        val empty = manager(FakeSource(emptyList()), dir)

        // Successful op: the PIN buffer is wiped after the device interaction.
        val goodPin = "123456".toCharArray()
        assertTrue(ready.listOathAccounts(goodPin, HardwareKeyManager.CancelToken())
            is HardwareKeyManager.Result.Ok)
        assertTrue(goodPin.all { it == ' ' })

        // No-key path: the PIN never reaches a device but must still be wiped.
        val noKeyPin = "999999".toCharArray()
        assertTrue(empty.listOathAccounts(noKeyPin, HardwareKeyManager.CancelToken())
            is HardwareKeyManager.Result.NoKey)
        assertTrue(noKeyPin.all { it == ' ' })

        // Pre-canceled path: same rule.
        val canceledPin = "424242".toCharArray()
        val token = HardwareKeyManager.CancelToken().also { it.cancel() }
        assertTrue(ready.listOathAccounts(canceledPin, token)
            is HardwareKeyManager.Result.Canceled)
        assertTrue(canceledPin.all { it == ' ' })

        // Hardware unlock with no enrollment returns Failed before device selection; wipe anyway.
        val unlockPin = "313370".toCharArray()
        assertTrue(ready.unlockWithKey(unlockPin, HardwareKeyManager.CancelToken())
            is HardwareKeyManager.Result.Failed)
        assertTrue(unlockPin.all { it == ' ' })
    }

    @Test
    fun closeForwardsToTheDeviceSource() {
        val dir = Files.createTempDirectory("offlineotp-hw-").toFile()
        val source = FakeSource(emptyList())
        manager(source, dir).close()
        assertTrue(source.closed)
    }
}

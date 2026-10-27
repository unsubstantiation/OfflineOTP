package com.yubico.yubikit.android.transport.nfc

class NfcConfiguration {
    fun timeout(milliseconds: Int): NfcConfiguration = this
}
class NfcNotAvailable : Exception()

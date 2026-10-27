package com.yubico.yubikit.fido.client.clientdata

class ClientDataProvider {
    companion object { fun fromHash(hash: ByteArray): ClientDataProvider = ClientDataProvider() }
}

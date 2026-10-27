// API-shape stand-ins only; they do not emulate a security key or perform cryptography.
package com.yubico.yubikit.core

interface YubiKeyDevice {
    fun <T> openConnection(type: Class<T>): T
}

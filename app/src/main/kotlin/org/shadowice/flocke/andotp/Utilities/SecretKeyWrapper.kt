/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.shadowice.flocke.andotp.Utilities

import android.annotation.SuppressLint

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyStore

import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * Wraps [SecretKey] instances using a public/private key pair stored in
 * the platform [KeyStore]. This allows us to protect symmetric keys with
 * hardware-backed crypto, if provided by the device.
 *
 * See [key wrapping](http://en.wikipedia.org/wiki/Key_Wrap) for more details.
 *
 * Not inherently thread safe.
 */
open class SecretKeyWrapper
@SuppressLint("GetInstance") // The RSA transformation JCA names is "RSA/ECB/PKCS1Padding"; the ECB warning is a false positive here.
@Throws(GeneralSecurityException::class, IOException::class)
constructor(private val pair: KeyPair) {
    private val cipher: Cipher = Cipher.getInstance(Constants.ALGORITHM_ASYMMETRIC)

    /**
     * Wrap a [SecretKey] using the public key assigned to this wrapper.
     * Use [unwrap] to later recover the original [SecretKey].
     *
     * @return a wrapped version of the given [SecretKey] that can be
     *         safely stored on untrusted storage.
     */
    @Throws(GeneralSecurityException::class)
    fun wrap(key: SecretKey): ByteArray {
        cipher.init(Cipher.WRAP_MODE, pair.public)
        return cipher.wrap(key)
    }

    /**
     * Unwrap a [SecretKey] using the private key assigned to this
     * wrapper.
     *
     * @param blob a wrapped [SecretKey] as previously returned by
     *            [wrap].
     */
    @Throws(GeneralSecurityException::class)
    fun unwrap(blob: ByteArray): SecretKey {
        cipher.init(Cipher.UNWRAP_MODE, pair.private)

        return cipher.unwrap(blob, "AES", Cipher.SECRET_KEY) as SecretKey
    }
}

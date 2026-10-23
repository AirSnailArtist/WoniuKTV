package com.pocketktv.app

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

/** Kotlin adaptation of SheltonZhu/115driver pkg/crypto/m115, MIT.
 * Copyright (c) 2022 sheltonzhu. See assets/licenses/115driver.txt.
 * Only implements the upstream client's request/response transport encoding.
 */
object SessionCipher {
    private val seed = ("f0e569aebfdcbf8a1a45e8be7da673b8de8fe7c445da86c49b648b146ab4f1aa" +
        "3801359e26692c86006b4fa5363462a62a966818f24afdbd6b978f4d8f8913b7" +
        "6c8e93ed0e0d483ed72f88d8fefe7e8650954fd1eb832634db667b9c7e9d7a81" +
        "32eab633de3aa95934663baaba816048b9d5819cf86c8477ff5478265fbee81e" +
        "369f34805c452c9b76d51b8fccc3b8f5").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val clientKey = "7806ad4c33865d184c013f46".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val modulus = BigInteger("8686980c0f5a24c4b9d43020cd2c22703ff3f450756529058b1cf88f09b86021" +
        "36477198a6e2683149659bd122c33592fdb5ad47944ad1ea4d36c6b172aad633" +
        "8c3bb6ac6227502d010993ac967d1aef00f0c8e038de2e4d3bc2ec368af2e9f1" +
        "0a6f1eda4f7262f136420c07c331b871bf139f74f3010e3c4fe57df3afb71683", 16)
    private val exponent = BigInteger.valueOf(65537)
    fun newKey() = ByteArray(16).also { SecureRandom().nextBytes(it) }
    internal fun derive(key: ByteArray, size: Int) = ByteArray(size) { i ->
        (((key[i].toInt() and 255) + (seed[size*i].toInt() and 255)) xor (seed[size*(size-i-1)].toInt() and 255)).toByte()
    }
    internal fun xor(data: ByteArray, key: ByteArray) {
        val prefix = data.size % 4
        for (i in data.indices) {
            val k = if (i < prefix) i % key.size else (i-prefix) % key.size
            data[i] = (data[i].toInt() xor key[k].toInt()).toByte()
        }
    }
    fun encode(text: String, key: ByteArray): String {
        require(key.size == 16)
        val data = text.toByteArray(Charsets.UTF_8)
        xor(data, derive(key, 4)); data.reverse(); xor(data, clientKey)
        val payload = key + data
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)))
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < payload.size) {
            val size = minOf(117, payload.size-offset)
            out.write(cipher.doFinal(payload, offset, size)); offset += size
        }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }
    fun decode(text: String, key: ByteArray): String {
        val bytes = Base64.getDecoder().decode(text)
        require(bytes.isNotEmpty() && bytes.size % 128 == 0) { "115 返回的加密数据不完整" }
        val out = ByteArrayOutputStream()
        for (chunk in bytes.asList().chunked(128)) {
            val data = BigInteger(1, chunk.toByteArray()).modPow(exponent, modulus).toByteArray()
            val start = data.indexOfFirst { it != 0.toByte() }
            require(start >= 0)
            val zero = (start+1 until data.size).firstOrNull { data[it] == 0.toByte() }
                ?: error("115 返回的加密数据无法解析")
            out.write(data, zero+1, data.size-zero-1)
        }
        val plain = out.toByteArray()
        require(plain.size >= 16)
        val data = plain.copyOfRange(16, plain.size)
        xor(data, derive(plain.copyOfRange(0, 16), 12)); data.reverse(); xor(data, derive(key, 4))
        return data.toString(Charsets.UTF_8)
    }
}

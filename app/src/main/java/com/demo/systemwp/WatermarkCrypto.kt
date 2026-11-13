package com.demo.systemwp

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 屏幕水印载荷的加密封装：AES-256-GCM，密钥由 Android Keystore 生成且不可导出。
 *
 * 由此得到两个性质：
 * 1. 载荷在截图里即使被完整还原，也只是密文，人类不可读、外部无法伪造；
 * 2. 只有本机、本应用能解出水印 —— 代价是卸载或清除数据后，历史截图将永久无法解析。
 */
object WatermarkCrypto {

    const val KEY_VERSION: Byte = 1

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "systemwp_screen_wm_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8

    /** 返回 [版本 1B][IV 12B][密文 + 16B 认证标签]。 */
    fun seal(plain: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            if (iv.size != IV_BYTES) return null
            val sealed = cipher.doFinal(plain)
            ByteArray(1 + IV_BYTES + sealed.size).also {
                it[0] = KEY_VERSION
                System.arraycopy(iv, 0, it, 1, IV_BYTES)
                System.arraycopy(sealed, 0, it, 1 + IV_BYTES, sealed.size)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 认证失败、密钥不可用或版本不匹配时返回 null。 */
    fun open(sealed: ByteArray): ByteArray? {
        if (sealed.size <= 1 + IV_BYTES + TAG_BYTES) return null
        if (sealed[0] != KEY_VERSION) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES)
            )
            cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
        } catch (_: Exception) {
            null
        }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

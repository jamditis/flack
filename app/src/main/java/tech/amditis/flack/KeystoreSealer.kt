package tech.amditis.flack

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a key that never leaves Android Keystore. The key does not
 * require an unlocked screen, because car replies happen with the phone locked.
 */
class KeystoreSealer(private val alias: String = "flack_slack_tokens") : TokenSealer {

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val iv = cipher.iv
        check(iv.size == IV_BYTES)
        return iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size <= IV_BYTES) throw SealedDataLost()
        val key = key(create = false) ?: throw SealedDataLost()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return try {
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (_: AEADBadTagException) {
            // The data was damaged or sealed with another key.
            throw SealedDataLost()
        }
    }

    override fun destroy() {
        try {
            keyStore().deleteEntry(alias)
        } catch (_: GeneralSecurityException) {
            // Nothing to delete.
        }
    }

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(create: Boolean): SecretKey? {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

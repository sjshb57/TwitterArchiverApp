package io.github.twitterarchiver.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private val Context.dataStore by preferencesDataStore("secure_store")

/**
 * 安全存储：用 Android Keystore 生成 AES-GCM 密钥，加密后存入 DataStore。
 * 替代已废弃的 EncryptedSharedPreferences（2026 推荐做法）。
 *
 * 注意：即便如此，本地存储对逆向仍非绝对安全——因此管理版仅供用户自己使用，
 * PAT 由用户自行填入，泄露风险由用户掌控。
 */
class SecureStore(private val context: Context) {

    companion object {
        private const val KEY_ALIAS = "twitterarchiver_pat_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128

        private val KEY_PAT = stringPreferencesKey("pat_encrypted")
        private val KEY_PAT_IV = stringPreferencesKey("pat_iv")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** 保存 PAT（加密） */
    suspend fun savePat(pat: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(pat.toByteArray(Charsets.UTF_8))
        context.dataStore.edit { prefs ->
            prefs[KEY_PAT] = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs[KEY_PAT_IV] = Base64.encodeToString(iv, Base64.NO_WRAP)
        }
    }

    /** 读取 PAT（解密），无则返回 null */
    suspend fun getPat(): String? {
        val prefs = context.dataStore.data.first()
        val encB64 = prefs[KEY_PAT] ?: return null
        val ivB64 = prefs[KEY_PAT_IV] ?: return null
        return try {
            val encrypted = Base64.decode(encB64, Base64.NO_WRAP)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_LENGTH, iv)
            )
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val permanent = e is android.security.keystore.KeyPermanentlyInvalidatedException ||
                e is java.security.UnrecoverableKeyException ||
                e is javax.crypto.AEADBadTagException
            if (permanent) runCatching { clearPat() }
            null
        }
    }

    /** 清除 PAT（登出） */
    suspend fun clearPat() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_PAT)
            prefs.remove(KEY_PAT_IV)
        }
    }
}

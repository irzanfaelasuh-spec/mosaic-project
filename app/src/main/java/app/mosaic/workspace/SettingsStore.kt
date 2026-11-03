package app.mosaic.workspace

import android.content.Context
import android.graphics.Color
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsStore private constructor(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("mosaic_settings", Context.MODE_PRIVATE)

    init {
        // Remove the obsolete backend URL / backend token left behind by earlier builds.
        if (sp.contains("ai_endpoint") || sp.contains("ai_token")) sp.edit().remove("ai_endpoint").remove("ai_token").apply()
    }

    var setupDone: Boolean get() = sp.getBoolean("setup_done", false); set(v) = sp.edit().putBoolean("setup_done", v).apply()
    var warningShown: Boolean get() = sp.getBoolean("multi_warning_shown", false); set(v) = sp.edit().putBoolean("multi_warning_shown", v).apply()
    var themeIndex: Int get() = sp.getInt("theme_index", 0).coerceIn(0, 4); set(v) = sp.edit().putInt("theme_index", v.coerceIn(0, 4)).apply()
    var windowOpacity: Int get() = sp.getInt("window_opacity", 94).coerceIn(55, 100); set(v) = sp.edit().putInt("window_opacity", v.coerceIn(55, 100)).apply()
    var defaultWidth: Int get() = sp.getInt("window_width", 310).coerceIn(230, 620); set(v) = sp.edit().putInt("window_width", v.coerceIn(230, 620)).apply()
    var defaultHeight: Int get() = sp.getInt("window_height", 360).coerceIn(180, 760); set(v) = sp.edit().putInt("window_height", v.coerceIn(180, 760)).apply()
    var compactTiles: Boolean get() = sp.getBoolean("compact_tiles", false); set(v) = sp.edit().putBoolean("compact_tiles", v).apply()
    var reduceMotion: Boolean get() = sp.getBoolean("reduce_motion", false); set(v) = sp.edit().putBoolean("reduce_motion", v).apply()
    var barCount: Int get() = sp.getInt("bar_count", 36).coerceIn(12, 72); set(v) = sp.edit().putInt("bar_count", v.coerceIn(12, 72)).apply()
    var sensitivity: Float get() = sp.getFloat("sensitivity", 1.55f).coerceIn(.5f, 3.5f); set(v) = sp.edit().putFloat("sensitivity", v.coerceIn(.5f, 3.5f)).apply()
    var smoothing: Float get() = sp.getFloat("smoothing", .62f).coerceIn(.1f, .95f); set(v) = sp.edit().putFloat("smoothing", v.coerceIn(.1f, .95f)).apply()
    var barHeight: Int get() = sp.getInt("bar_height", 86).coerceIn(30, 100); set(v) = sp.edit().putInt("bar_height", v.coerceIn(30, 100)).apply()
    var barGap: Int get() = sp.getInt("bar_gap", 3).coerceIn(1, 9); set(v) = sp.edit().putInt("bar_gap", v.coerceIn(1, 9)).apply()
    var glow: Boolean get() = sp.getBoolean("glow", true); set(v) = sp.edit().putBoolean("glow", v).apply()
    var peaks: Boolean get() = sp.getBoolean("peaks", true); set(v) = sp.edit().putBoolean("peaks", v).apply()
    /** Gemini model id used for every AI request. Not secret; blank / invalid input falls back to the default. */
    var aiModel: String
        get() = AiClient.normalizeModel(sp.getString("ai_model", "") ?: "") ?: AiClient.DEFAULT_MODEL
        set(v) {
            val model = AiClient.normalizeModel(v)
            val editor = sp.edit()
            if (model == null || model == AiClient.DEFAULT_MODEL) editor.remove("ai_model") else editor.putString("ai_model", model)
            editor.apply()
        }

    /** Returns the saved Gemini API key, or null when none is saved (or it can no longer be decrypted). */
    fun geminiApiKeyOrNull(): String? {
        val stored = sp.getString("gemini_key_enc", null) ?: return null
        return try { KeyVault.decrypt(stored).takeIf { it.isNotBlank() } } catch (e: Exception) { null }
    }

    /** Encrypts the key with an Android Keystore AES-GCM key and stores only the ciphertext. */
    fun saveGeminiApiKey(raw: String): Result<Unit> {
        val key = normalizeApiKey(raw)
            ?: return Result.failure(IllegalArgumentException("That does not look like a valid API key. Paste the full key from Google AI Studio without spaces."))
        return try {
            val saved = sp.edit().putString("gemini_key_enc", KeyVault.encrypt(key)).commit()
            if (saved) Result.success(Unit) else Result.failure(IllegalStateException("The key could not be written to storage."))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Secure storage is unavailable on this device, so the key was not saved."))
        }
    }

    fun clearGeminiApiKey() {
        sp.edit().remove("gemini_key_enc").apply()
        KeyVault.deleteKey()
    }

    /** "AIza••••••••WXYZ" style preview for the Settings screen (never the full key). */
    fun maskedGeminiApiKey(): String? {
        val key = geminiApiKeyOrNull() ?: return null
        return if (key.length <= 8) "••••••••" else key.take(4) + "••••••••" + key.takeLast(4)
    }

    var savedNotes: String get() = sp.getString("saved_notes", "") ?: ""; set(v) = sp.edit().putString("saved_notes", v).apply()

    fun accent(): Int = when (themeIndex) {
        1 -> Color.rgb(137, 165, 255)
        2 -> Color.rgb(207, 149, 255)
        3 -> Color.rgb(246, 174, 111)
        4 -> Color.rgb(239, 139, 159)
        else -> Color.rgb(122, 216, 200)
    }

    companion object {
        fun from(context: Context) = SettingsStore(context)

        private val KEY_FORMAT = Regex("^[\\x21-\\x7E]{16,256}\$")

        /** Trims whitespace/quotes; returns null unless the text is 16-256 printable ASCII characters without spaces. */
        fun normalizeApiKey(raw: String): String? {
            val key = raw.trim().trim('"', '\'', '`').trim()
            return if (KEY_FORMAT.matches(key)) key else null
        }
    }
}

/** Tiny AES-256-GCM wrapper around a non-exportable Android Keystore key. */
private object KeyVault {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "mosaic_gemini_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        require(all.size > IV_BYTES) { "Stored value is damaged" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, all.copyOfRange(0, IV_BYTES)))
        return String(cipher.doFinal(all.copyOfRange(IV_BYTES, all.size)), Charsets.UTF_8)
    }

    fun deleteKey() {
        try { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS) } catch (e: Exception) { }
    }
}

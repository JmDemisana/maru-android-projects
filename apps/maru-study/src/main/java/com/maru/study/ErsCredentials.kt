package com.maru.study

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.time.LocalDate
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Login details for the TUP Enrollment Registration System. They stay on this
 * phone: the ID and birthday sit in private app storage, and the password is
 * encrypted with a key that never leaves the Android Keystore.
 */
data class ErsCredentials(
    val studentId: String = "",
    val password: String = "",
    val birthday: String = "",
) {
    val isComplete: Boolean
        get() = studentId.isNotBlank() && password.isNotBlank() && isValidErsBirthday(birthday)
}

class ErsCredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("maru_study_ers", Context.MODE_PRIVATE)

    fun load(): ErsCredentials = ErsCredentials(
        studentId = prefs.getString(KEY_ID, "").orEmpty(),
        password = prefs.getString(KEY_PASSWORD, null)
            ?.let { SecretBox.decrypt(it) }
            .orEmpty(),
        birthday = prefs.getString(KEY_BIRTHDAY, "").orEmpty(),
    )

    fun rememberLogin(): Boolean = prefs.getBoolean(KEY_REMEMBER, true)

    fun hasSavedLogin(): Boolean = prefs.contains(KEY_PASSWORD)

    fun save(credentials: ErsCredentials, remember: Boolean) {
        val editor = prefs.edit().putBoolean(KEY_REMEMBER, remember)
        if (!remember) {
            editor
                .remove(KEY_ID)
                .remove(KEY_BIRTHDAY)
                .remove(KEY_PASSWORD)
            editor.apply()
            return
        }

        val encrypted = runCatching { SecretBox.encrypt(credentials.password) }.getOrNull()
        editor
            .putString(KEY_ID, credentials.studentId.trim())
            .putString(KEY_BIRTHDAY, credentials.birthday.trim())
        if (encrypted != null) {
            editor.putString(KEY_PASSWORD, encrypted)
        }
        editor.apply()
    }

    private companion object {
        const val KEY_ID = "student_id"
        const val KEY_PASSWORD = "password"
        const val KEY_BIRTHDAY = "birthday"
        const val KEY_REMEMBER = "remember_login"
    }
}

private object SecretBox {

    private const val ALIAS = "maru_study_ers_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }

    fun decrypt(payload: String): String? = runCatching {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        if (bytes.size <= IV_BYTES) return@runCatching null
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val body = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

}

fun normalizeErsBirthday(raw: String): String {
    val digits = raw.filter { it.isDigit() }.take(8)
    return when {
        digits.length <= 2 -> digits
        digits.length <= 4 -> digits.substring(0, 2) + "/" + digits.substring(2)
        else -> digits.substring(0, 2) + "/" +
            digits.substring(2, 4) + "/" +
            digits.substring(4)
    }
}

fun isValidErsBirthday(value: String): Boolean = ersBirthdayDate(value) != null

fun ersBirthdayDate(value: String): LocalDate? {
    val parts = value.trim().split("/")
    if (parts.size != 3) return null
    val month = parts[0].toIntOrNull() ?: return null
    val day = parts[1].toIntOrNull() ?: return null
    val year = parts[2].toIntOrNull() ?: return null
    if (year < 1900 || month !in 1..12 || day !in 1..31) return null
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
}

fun formatErsBirthday(year: Int, month: Int, day: Int): String =
    "%02d/%02d/%04d".format(Locale.US, month, day, year)

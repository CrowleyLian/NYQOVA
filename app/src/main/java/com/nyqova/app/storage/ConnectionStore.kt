package com.nyqova.app.storage

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ConnectionProfile(val url: String, val username: String = "", val password: String = "", val domain: String = "") {
    fun activate() {
        val uri = Uri.parse(url)
        MusicSource.webDavBase = if(uri.scheme == "smb") null else url.trimEnd('/') + "/"
        MusicSource.username = username
        MusicSource.password = password
        if(uri.scheme == "smb") SmbSource.settings = SourceSettings(uri.host!!, uri.pathSegments.first(), username, password, domain)
        MusicSource.initialized = true
    }
}

/** The app stores only ciphertext; Android Keystore retains the non-exportable encryption key. */
object ConnectionStore {
    private const val ALIAS = "nyqova.connection.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun save(context: Context, profile: ConnectionProfile) {
        val json = JSONObject().put("url", profile.url).put("username", profile.username)
            .put("password", profile.password).put("domain", profile.domain).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        val prefs = context.getSharedPreferences("connection_secure", Context.MODE_PRIVATE)
        check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(data, Base64.NO_WRAP)).commit()) { "连接信息保存失败，请重试" }
        context.getSharedPreferences("source", Context.MODE_PRIVATE).edit().clear().apply()
    }
    fun load(context: Context): ConnectionProfile? {
        val prefs = context.getSharedPreferences("connection_secure", Context.MODE_PRIVATE)
        val data = prefs.getString("data", null) ?: return null
        val iv = prefs.getString("iv", null) ?: error("保存的连接信息不完整，请重新设置")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        val json = JSONObject(String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8))
        return ConnectionProfile(json.getString("url"), json.optString("username"), json.optString("password"), json.optString("domain"))
    }
}

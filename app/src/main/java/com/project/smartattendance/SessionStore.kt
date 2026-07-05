package com.project.smartattendance

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

private const val SESSION_PREFS = "smartattendance_session"
private const val SESSION_KEY = "auth_session_json"

object SessionStore {
    private fun securePrefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        SESSION_PREFS,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(context: Context, session: AuthSession) {
        val payload = JSONObject()
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("user_id", session.userId)
            .put("email", session.email)

        securePrefs(context)
            .edit()
            .putString(SESSION_KEY, payload.toString())
            .apply()
    }

    fun load(context: Context): AuthSession? {
        val raw = securePrefs(context)
            .getString(SESSION_KEY, null)
            ?: return null

        return runCatching {
            val json = JSONObject(raw)
            AuthSession(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                userId = json.getString("user_id"),
                email = json.getString("email")
            )
        }.getOrNull()
    }

    fun clear(context: Context) {
        securePrefs(context)
            .edit()
            .remove(SESSION_KEY)
            .apply()
    }
}

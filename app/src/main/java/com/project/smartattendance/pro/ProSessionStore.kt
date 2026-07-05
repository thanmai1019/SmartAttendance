package com.project.smartattendance.pro

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class TeacherSessionState(
    val sessionId: String,
    val otp: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long
)

object ProSessionStore {
    private const val PREFS_NAME = "smartattendance_pro_teacher_session"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_OTP = "otp"
    private const val KEY_START = "start_time"
    private const val KEY_END = "end_time"

    private fun securePrefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(context: Context, state: TeacherSessionState) {
        securePrefs(context)
            .edit()
            .putString(KEY_SESSION_ID, state.sessionId)
            .putString(KEY_OTP, state.otp)
            .putLong(KEY_START, state.startTimeMillis)
            .putLong(KEY_END, state.endTimeMillis)
            .apply()
    }

    fun load(context: Context): TeacherSessionState? {
        val prefs = securePrefs(context)
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val otp = prefs.getString(KEY_OTP, null) ?: return null
        val start = prefs.getLong(KEY_START, -1L)
        val end = prefs.getLong(KEY_END, -1L)
        if (start <= 0L || end <= 0L) return null
        return TeacherSessionState(
            sessionId = sessionId,
            otp = otp,
            startTimeMillis = start,
            endTimeMillis = end
        )
    }

    fun clear(context: Context) {
        securePrefs(context)
            .edit()
            .clear()
            .apply()
    }
}

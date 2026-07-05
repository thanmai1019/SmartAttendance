package com.project.smartattendance.pro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PendingAttendance(
    val studentName: String,
    val studentId: String,
    val studentUuid: String?,
    val otp: String,
    val queuedAt: Long
)

object PendingAttendanceStore {
    private const val PREFS_NAME = "smartattendance_pending_attendance"
    private const val KEY_QUEUE = "queue"

    fun enqueue(context: Context, item: PendingAttendance) {
        val queue = load(context).toMutableList()
        queue.add(item)
        save(context, queue)
    }

    fun load(context: Context): List<PendingAttendance> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_QUEUE, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val entry = array.getJSONObject(index)
                PendingAttendance(
                    studentName = entry.getString("student_name"),
                    studentId = entry.getString("student_id"),
                    studentUuid = entry.optString("student_uuid").ifBlank { null },
                    otp = entry.getString("otp"),
                    queuedAt = entry.optLong("queued_at", System.currentTimeMillis())
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, queue: List<PendingAttendance>) {
        val array = JSONArray()
        queue.forEach { item ->
            array.put(
                JSONObject()
                    .put("student_name", item.studentName)
                    .put("student_id", item.studentId)
                    .put("student_uuid", item.studentUuid ?: "")
                    .put("otp", item.otp)
                    .put("queued_at", item.queuedAt)
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUEUE, array.toString())
            .apply()
    }
}

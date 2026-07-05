package com.project.smartattendance
import android.graphics.Bitmap
import android.util.Base64
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.io.ByteArrayOutputStream
const val SESSION_DURATION_MILLIS = 5 * 60_000L
const val VERIFICATION_VALIDITY_MILLIS = 90_000L
const val MIN_ACCEPTABLE_BLE_RSSI = -78
const val MIN_FACE_MATCH_SCORE = 0.40f
private val dashboardDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a")
        .withZone(ZoneId.systemDefault())
private val calendarDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy")
fun generateCode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return (1..6)
        .map { chars.random() }
        .joinToString("")
}
fun nowMillis(): Long = System.currentTimeMillis()
fun nowIsoString(): String = millisToIsoString(nowMillis())
fun millisToIsoString(millis: Long): String {
    return Instant.ofEpochMilli(millis).toString()
}
fun parseSupabaseTimestamp(value: Any?): Long? {
    return when (value) {
        null -> null
        is Number -> value.toLong()
        is String -> parseTimestampString(value)
        else -> null
    }
}

private fun parseTimestampString(value: String): Long? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    return trimmed.toLongOrNull() ?: tryParseIsoTimestamp(trimmed)
}

private fun tryParseIsoTimestamp(value: String): Long? {
    return try {
        Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

fun formatCountdown(remainingMillis: Long): String {
    val totalSeconds = remainingMillis.coerceAtLeast(0L) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) {
        "${minutes}m ${seconds}s"
    } else {
        "${seconds}s"
    }
}
fun formatDateTime(epochMillis: Long): String {
    return dashboardDateFormatter.format(Instant.ofEpochMilli(epochMillis))
}
fun epochMillisToLocalDate(epochMillis: Long): LocalDate {
    return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
}
fun formatCalendarDate(date: LocalDate): String {
    return calendarDateFormatter.format(date)
}
fun localDateToStorageString(date: LocalDate): String = date.toString()
fun parseStoredLocalDate(value: String?): LocalDate? {
    return value?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
fun classPeriodLabel(period: Int?): String {
    return period?.let { "Period $it" } ?: "Period not set"
}
fun isVerificationFresh(verifiedAtMillis: Long?): Boolean {
    if (verifiedAtMillis == null) return false
    return nowMillis() - verifiedAtMillis <= VERIFICATION_VALIDITY_MILLIS
}
fun hasFaceEnrollment(profile: UserProfile): Boolean {
    return !profile.faceTemplate.isNullOrBlank() && !profile.faceImageBase64.isNullOrBlank()
}
fun bitmapToBase64(bitmap: Bitmap): String {
    val outputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, outputStream)
    return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
}
fun isRunningOnEmulator(): Boolean {
    return android.os.Build.FINGERPRINT.startsWith("generic") ||
        android.os.Build.FINGERPRINT.lowercase().contains("emulator") ||
        android.os.Build.MODEL.contains("Emulator", ignoreCase = true) ||
        android.os.Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
        android.os.Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
        (android.os.Build.BRAND.startsWith("generic") && android.os.Build.DEVICE.startsWith("generic")) ||
        android.os.Build.PRODUCT == "google_sdk"
}

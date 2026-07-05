package com.project.smartattendance.pro

fun readableError(throwable: Throwable): String {
    val message = throwable.message.orEmpty()
    return when {
        message.contains("Unable to resolve host", ignoreCase = true) ||
            message.contains("Failed to connect", ignoreCase = true) -> {
            "No internet connection. Check your network and try again."
        }

        message.contains("row-level security", ignoreCase = true) ||
            message.contains("permission denied", ignoreCase = true) ||
            message.contains("JWT", ignoreCase = true) -> {
            "Supabase access policy is blocking this request. Apply the SmartAttendance Pro SQL setup."
        }

        message.isBlank() -> "Something went wrong. Please try again."
        else -> message
    }
}

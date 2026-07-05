package com.project.smartattendance

data class Session(
    val id: String? = null,
    val code: String,
    val teacherId: String? = null,
    val teacherName: String = "Teacher",
    val classDate: String? = null,
    val classPeriod: Int? = null,
    val createdAt: Long,
    val expiresAt: Long = createdAt + SESSION_DURATION_MILLIS,
    val isActive: Boolean = true
)

data class AttendanceRecord(
    val id: String? = null,
    val studentId: String? = null,
    val studentName: String,
    val studentRollNumber: String? = null,
    val sessionId: String,
    val sessionCode: String,
    val classDate: String? = null,
    val classPeriod: Int? = null,
    val markedAt: Long,
    val teacherName: String? = null,
    val teacherId: String? = null,
    val bluetoothVerified: Boolean = false,
    val faceVerified: Boolean = false,
    val faceMatchScore: Double? = null,
    val bluetoothRssi: Int? = null
)

data class AttendanceResult(
    val success: Boolean,
    val message: String
)

data class SessionSaveResult(
    val session: Session? = null,
    val message: String
)

enum class UserRole(val dbValue: String, val label: String) {
    Teacher("teacher", "Teacher"),
    Student("student", "Student");

    companion object {
        fun fromDbValue(value: String?): UserRole {
            return entries.firstOrNull { it.dbValue.equals(value, ignoreCase = true) } ?: Student
        }
    }
}

data class UserProfile(
    val id: String,
    val email: String,
    val fullName: String,
    val rollNumber: String? = null,
    val role: UserRole = UserRole.Student,
    val createdAt: Long = nowMillis(),
    val faceTemplate: String? = null,
    val faceEnrolledAt: Long? = null,
    val faceImageBase64: String? = null
)

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String
)

data class AuthResult(
    val success: Boolean,
    val message: String,
    val session: AuthSession? = null,
    val profile: UserProfile? = null
)

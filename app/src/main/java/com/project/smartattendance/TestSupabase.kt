package com.project.smartattendance

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val JSON_OBJECT_ACCEPT = "application/vnd.pgrst.object+json"

private fun networkAwareError(prefix: String, exception: Exception): String {
    val reason = when (exception) {
        is UnknownHostException -> "Cannot reach Supabase host. Check internet, DNS, VPN/Private DNS, then retry."
        is SocketTimeoutException -> "Request timed out. Check internet stability and retry."
        is ConnectException -> "Could not connect to server. Check network and Supabase status."
        else -> exception.message ?: "unknown error"
    }
    return "$prefix: $reason"
}

private fun HttpRequestBuilder.applySupabaseHeaders(accessToken: String? = null) {
    header("apikey", SupabaseClient.apiKey)
    if (!accessToken.isNullOrBlank()) {
        header(HttpHeaders.Authorization, "Bearer $accessToken")
    } else {
        header(HttpHeaders.Authorization, "Bearer ${SupabaseClient.apiKey}")
    }
}

private fun HttpRequestBuilder.applyAuthenticatedHeaders(accessToken: String) {
    require(accessToken.isNotBlank()) { "Authenticated access token is required for this request." }
    header("apikey", SupabaseClient.apiKey)
    header(HttpHeaders.Authorization, "Bearer $accessToken")
}

private fun HttpRequestBuilder.acceptSingleObject() {
    header(HttpHeaders.Accept, JSON_OBJECT_ACCEPT)
}

suspend fun signUpUser(
    email: String,
    password: String,
    fullName: String,
    rollNumber: String,
    role: UserRole,
    faceTemplate: String? = null,
    faceImageBase64: String? = null
): AuthResult {
    return try {
        val payload = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .put(
                "data",
                    JSONObject()
                        .put("full_name", fullName.trim())
                        .put("roll_number", rollNumber.trim())
                        .put("role", role.dbValue)
                        .put("face_template", faceTemplate ?: JSONObject.NULL)
                        .put("face_image_base64", faceImageBase64 ?: JSONObject.NULL)
                        .put(
                            "face_enrolled_at",
                            if (faceTemplate != null) millisToIsoString(nowMillis()) else JSONObject.NULL
                        )
            )

        val response = SupabaseClient.httpClient.post("${SupabaseClient.authUrl}/signup") {
            applySupabaseHeaders()
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val body = response.bodyAsText()
        if (!response.isSuccess()) {
            val rawError = extractSupabaseError(response, body)
            val duplicateEmail =
                rawError.contains("already registered", ignoreCase = true) ||
                    rawError.contains("already been registered", ignoreCase = true) ||
                    rawError.contains("user already registered", ignoreCase = true) ||
                    rawError.contains("duplicate key", ignoreCase = true) ||
                    rawError.contains("email", ignoreCase = true) && rawError.contains("exists", ignoreCase = true)
            return if (duplicateEmail) {
                AuthResult(false, "This email is already registered. Please log in with the same email.")
            } else {
                AuthResult(false, rawError)
            }
        }

        val session = parseAuthSession(body)
        if (session == null) {
            val loginResult = signInUser(email, password)
            return if (loginResult.success) {
                loginResult.copy(message = "Account created successfully")
            } else {
                AuthResult(
                    success = false,
                    message = "Signup succeeded, but automatic login failed. Disable email confirmation in Supabase Auth or log in manually."
                )
            }
        }

        val profile = UserProfile(
            id = session.userId,
            email = session.email,
            fullName = fullName.trim(),
            rollNumber = rollNumber.trim().ifBlank { null },
            role = role,
            faceTemplate = faceTemplate,
            faceEnrolledAt = faceTemplate?.let { nowMillis() },
            faceImageBase64 = faceImageBase64
        )

        val upsertResult = upsertUserProfile(session.accessToken, profile)
        if (!upsertResult.success) {
            return if (isMissingProfilesTableError(upsertResult.message)) {
                AuthResult(
                    success = true,
                    message = "Account created. Profiles table is missing in Supabase, so the app is using auth metadata until you run the setup SQL.",
                    session = session,
                    profile = profile
                )
            } else {
                AuthResult(false, upsertResult.message)
            }
        }

        AuthResult(
            success = true,
            message = "Account created successfully",
            session = session,
            profile = upsertResult.profile ?: profile
        )
    } catch (exception: Exception) {
        AuthResult(false, networkAwareError("Signup failed", exception))
    }
}

suspend fun signInUser(email: String, password: String): AuthResult {
    return try {
        val payload = JSONObject()
            .put("email", email.trim())
            .put("password", password)

        val response = SupabaseClient.httpClient.post("${SupabaseClient.authUrl}/token?grant_type=password") {
            applySupabaseHeaders()
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val body = response.bodyAsText()
        if (!response.isSuccess()) {
            return AuthResult(false, extractSupabaseError(response, body))
        }

        val session = parseAuthSession(body)
            ?: return AuthResult(false, "Supabase did not return a valid login session.")
        val authUser = parseAuthUser(body)
        val existingProfile = fetchUserProfile(session.accessToken, session.userId)
        val profile = existingProfile ?: createProfileFromAuthUser(session, authUser)

        if (existingProfile == null) {
            val upsertResult = upsertUserProfile(session.accessToken, profile)
            if (!upsertResult.success) {
                if (!isMissingProfilesTableError(upsertResult.message)) {
                    return AuthResult(false, upsertResult.message)
                }
                return AuthResult(
                    success = true,
                    message = "Logged in. Profiles table is missing in Supabase, so the app is using auth metadata until you run the setup SQL.",
                    session = session,
                    profile = profile
                )
            }
        }

        AuthResult(
            success = true,
            message = "Logged in successfully",
            session = session,
            profile = fetchUserProfile(session.accessToken, session.userId) ?: profile
        )
    } catch (exception: Exception) {
        AuthResult(false, networkAwareError("Login failed", exception))
    }
}

suspend fun restoreUserSession(savedSession: AuthSession): AuthResult {
    return try {
        val refreshedSession = refreshSession(savedSession)
            ?: return AuthResult(false, "Saved login session has expired. Please sign in again.")
        val profile = fetchUserProfile(refreshedSession.accessToken, refreshedSession.userId)
            ?: fetchAuthenticatedUserProfile(refreshedSession)
            ?: return AuthResult(false, "Profile record was not found for this account.")

        AuthResult(
            success = true,
            message = "Session restored",
            session = refreshedSession,
            profile = profile
        )
    } catch (exception: Exception) {
        AuthResult(false, networkAwareError("Could not restore session", exception))
    }
}

private suspend fun refreshSession(savedSession: AuthSession): AuthSession? {
    val payload = JSONObject().put("refresh_token", savedSession.refreshToken)
    val response = SupabaseClient.httpClient.post("${SupabaseClient.authUrl}/token?grant_type=refresh_token") {
        applySupabaseHeaders()
        contentType(ContentType.Application.Json)
        setBody(payload.toString())
    }
    val body = response.bodyAsText()
    return if (response.isSuccess()) parseAuthSession(body) else null
}

private suspend fun fetchAuthenticatedUserProfile(session: AuthSession): UserProfile? {
    val response = SupabaseClient.httpClient.get("${SupabaseClient.authUrl}/user") {
        applyAuthenticatedHeaders(session.accessToken)
    }
    val body = response.bodyAsText()
    if (!response.isSuccess()) return null
    val authUser = runCatching { JSONObject(body) }.getOrNull() ?: return null
    return createProfileFromAuthUser(session, authUser)
}

private fun parseAuthSession(body: String): AuthSession? {
    val json = JSONObject(body)
    val user = json.optJSONObject("user") ?: return null
    val accessToken = json.optString("access_token")
    val refreshToken = json.optString("refresh_token")
    val userId = user.optString("id")
    val email = user.optString("email")

    if (accessToken.isBlank() || refreshToken.isBlank() || userId.isBlank() || email.isBlank()) {
        return null
    }

    return AuthSession(
        accessToken = accessToken,
        refreshToken = refreshToken,
        userId = userId,
        email = email
    )
}

private fun parseAuthUser(body: String): JSONObject? {
    return runCatching { JSONObject(body).optJSONObject("user") }.getOrNull()
}

private fun createProfileFromAuthUser(
    session: AuthSession,
    authUser: JSONObject?
): UserProfile {
    val metadata = authUser?.optJSONObject("user_metadata")
    val fullName = metadata?.optString("full_name").orEmpty().ifBlank {
        session.email.substringBefore("@").replaceFirstChar { char -> char.uppercase() }
    }
    val rollNumber = metadata?.optString("roll_number").orEmpty().ifBlank { null }
    val role = UserRole.fromDbValue(metadata?.optString("role"))

    return UserProfile(
        id = session.userId,
        email = session.email,
        fullName = fullName,
        rollNumber = rollNumber,
        role = role,
        faceTemplate = metadata?.optString("face_template").orEmpty().ifBlank { null },
        faceEnrolledAt = parseSupabaseTimestamp(metadata?.opt("face_enrolled_at")),
        faceImageBase64 = metadata?.optString("face_image_base64").orEmpty().ifBlank { null }
    )
}

private suspend fun upsertUserProfile(accessToken: String, profile: UserProfile): AuthResult {
    return try {
        val payload = JSONObject()
            .put("id", profile.id)
            .put("email", profile.email)
            .put("full_name", profile.fullName)
            .put("roll_number", profile.rollNumber ?: JSONObject.NULL)
            .put("role", profile.role.dbValue)
            .put("created_at", millisToIsoString(profile.createdAt))
        if (!profile.faceTemplate.isNullOrBlank()) {
            payload.put("face_template", profile.faceTemplate)
        }
        if (profile.faceEnrolledAt != null) {
            payload.put("face_enrolled_at", millisToIsoString(profile.faceEnrolledAt))
        }
        if (!profile.faceImageBase64.isNullOrBlank()) {
            payload.put("face_image_base64", profile.faceImageBase64)
        }

        val response = SupabaseClient.httpClient.post("${SupabaseClient.restUrl}/profiles") {
            applyAuthenticatedHeaders(accessToken)
            header("Prefer", "resolution=merge-duplicates,return=representation")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val body = response.bodyAsText()
        if (!response.isSuccess()) {
            return AuthResult(false, "Profile could not be saved: ${extractSupabaseError(response, body)}")
        }

        val savedProfile = parseProfiles(body).firstOrNull() ?: profile
        AuthResult(true, "Profile saved", profile = savedProfile)
    } catch (exception: Exception) {
        AuthResult(false, "Profile could not be saved: ${exception.message ?: "unknown error"}")
    }
}

suspend fun fetchUserProfile(accessToken: String, userId: String): UserProfile? {
    return try {
        val response = SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/profiles") {
            applyAuthenticatedHeaders(accessToken)
            parameter("id", "eq.$userId")
            parameter("select", "*")
            parameter("limit", 1)
        }
        val body = response.bodyAsText()
        if (!response.isSuccess()) return null
        parseProfiles(body).firstOrNull()
    } catch (_: Exception) {
        null
    }
}

suspend fun saveFaceEnrollment(
    accessToken: String,
    profile: UserProfile,
    faceTemplate: String,
    faceImageBase64: String
): AuthResult {
    val updatedProfile = profile.copy(
        faceTemplate = faceTemplate,
        faceEnrolledAt = nowMillis(),
        faceImageBase64 = faceImageBase64
    )
    val result = upsertUserProfile(accessToken, updatedProfile)
    return if (!result.success && isMissingFaceColumnsError(result.message)) {
        AuthResult(
            success = false,
            message = "Face enrollment needs the new Supabase columns. Run the updated supabase_setup.sql, then try again."
        )
    } else {
        result.copy(profile = updatedProfile)
    }
}

suspend fun saveSessionDetailed(
    code: String,
    teacherProfile: UserProfile,
    accessToken: String,
    classDate: String,
    classPeriod: Int,
    expiresAt: Long
): SessionSaveResult {
    return try {
        val createdAt = nowMillis()
        expireTeacherActiveSessions(accessToken, teacherProfile)
        val insertAttempts = listOf(
            JSONObject()
                .put("code", code)
                .put("teacher_id", teacherProfile.id)
                .put("teacher_name", teacherProfile.fullName)
                .put("class_date", classDate)
                .put("class_period", classPeriod)
                .put("created_at", millisToIsoString(createdAt))
                .put("expires_at", millisToIsoString(expiresAt))
                .put("is_active", true)
        )

        val insertResult = postWithFallback(
            table = "sessions",
            payloads = insertAttempts,
            accessToken = accessToken,
            preferRepresentation = true
        )

        if (!insertResult.response.isSuccess()) {
            val errorMessage = if (
                insertResult.errorMessage.contains("teacher_id", ignoreCase = true) ||
                insertResult.errorMessage.contains("expires_at", ignoreCase = true) ||
                insertResult.errorMessage.contains("is_active", ignoreCase = true) ||
                insertResult.errorMessage.contains("class_date", ignoreCase = true) ||
                insertResult.errorMessage.contains("class_period", ignoreCase = true)
            ) {
                "Session columns in Supabase are out of date. Please run the updated supabase_setup.sql."
            } else {
                insertResult.errorMessage
            }
            SessionSaveResult(session = null, message = errorMessage)
        } else {
            val session = parseSessions(insertResult.body).firstOrNull() ?: Session(
                code = code,
                teacherId = teacherProfile.id,
                teacherName = teacherProfile.fullName,
                classDate = classDate,
                classPeriod = classPeriod,
                createdAt = createdAt,
                expiresAt = expiresAt
            )
            SessionSaveResult(
                session = session,
                message = "Attendance session created successfully"
            )
        }
    } catch (exception: Exception) {
        SessionSaveResult(
            session = null,
            message = "Session save failed: ${exception.message ?: "unknown error"}"
        )
    }
}

private suspend fun expireTeacherActiveSessions(
    accessToken: String,
    teacherProfile: UserProfile
) {
    val payload = JSONObject().put("is_active", false)
    val requests = buildList {
        add(suspend {
            SupabaseClient.httpClient.patch("${SupabaseClient.restUrl}/sessions") {
                applyAuthenticatedHeaders(accessToken)
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                parameter("teacher_id", "eq.${teacherProfile.id}")
                parameter("is_active", "eq.true")
                setBody(payload.toString())
            }
        })
        add(suspend {
            SupabaseClient.httpClient.patch("${SupabaseClient.restUrl}/sessions") {
                applyAuthenticatedHeaders(accessToken)
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                parameter("teacher_name", "eq.${teacherProfile.fullName}")
                parameter("is_active", "eq.true")
                setBody(payload.toString())
            }
        })
    }

    requests.forEach { request ->
        runCatching { request() }
    }
}

suspend fun loadTeacherSessions(
    accessToken: String,
    teacherProfile: UserProfile
): List<Session> {
    return try {
        val response = SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/sessions") {
            applyAuthenticatedHeaders(accessToken)
            parameter("select", "*")
            parameter("teacher_id", "eq.${teacherProfile.id}")
            parameter("order", "created_at.desc")
            parameter("limit", 20)
        }
        val body = response.bodyAsText()
        if (response.isSuccess()) {
            parseSessions(body)
        } else {
            loadTeacherSessionsByName(accessToken, teacherProfile.fullName)
        }
    } catch (_: Exception) {
        loadTeacherSessionsByName(accessToken, teacherProfile.fullName)
    }
}

private suspend fun loadTeacherSessionsByName(
    accessToken: String,
    teacherName: String
): List<Session> {
    return try {
        val response = SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/sessions") {
            applyAuthenticatedHeaders(accessToken)
            parameter("select", "*")
            parameter("teacher_name", "eq.$teacherName")
            parameter("order", "created_at.desc")
            parameter("limit", 20)
        }
        val body = response.bodyAsText()
        if (response.isSuccess()) parseSessions(body) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}

data class SessionLookupResult(
    val session: Session? = null,
    val message: String
)

suspend fun lookupActiveSessionByCode(code: String, accessToken: String): SessionLookupResult {
    val normalizedCode = code.trim().uppercase()
    if (normalizedCode.length != 6) {
        return SessionLookupResult(message = "Enter the full 6-character attendance code.")
    }

    return try {
        suspend fun querySessionsWithAuthHeaders(usePublicHeaders: Boolean): Pair<List<Session>, String?> {
            val response = SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/sessions") {
                if (usePublicHeaders) {
                    applySupabaseHeaders()
                } else {
                    applyAuthenticatedHeaders(accessToken)
                }
                parameter("code", "eq.$normalizedCode")
                parameter("select", "*")
                parameter("order", "created_at.desc")
                parameter("limit", 10)
            }
            val body = response.bodyAsText()
            return if (response.isSuccess()) {
                parseSessions(body) to null
            } else {
                emptyList<Session>() to extractSupabaseError(response, body)
            }
        }

        val (authenticatedSessions, authenticatedError) = querySessionsWithAuthHeaders(usePublicHeaders = false)
        val sessions = if (authenticatedSessions.isNotEmpty()) {
            authenticatedSessions
        } else {
            val (publicSessions, publicError) = querySessionsWithAuthHeaders(usePublicHeaders = true)
            if (publicSessions.isNotEmpty()) {
                publicSessions
            } else {
                if (!authenticatedError.isNullOrBlank() || !publicError.isNullOrBlank()) {
                    return SessionLookupResult(
                        message = "Session lookup failed: ${authenticatedError ?: publicError ?: "Unknown backend error"}"
                    )
                }
                emptyList()
            }
        }

        val matchingSession = sessions.firstOrNull { session ->
            session.isActive && session.expiresAt > nowMillis()
        }

        if (matchingSession != null) {
            val sessionDate = parseStoredLocalDate(matchingSession.classDate)
                ?: epochMillisToLocalDate(matchingSession.createdAt)
            SessionLookupResult(
                session = matchingSession,
                message = "Attendance session found for ${formatCalendarDate(sessionDate)} ${classPeriodLabel(matchingSession.classPeriod)}."
            )
        } else {
            SessionLookupResult(
                message = "No active attendance session was found for code $normalizedCode."
            )
        }
    } catch (_: Exception) {
        SessionLookupResult(
            message = "Session lookup failed. Check your internet connection and Supabase session policies."
        )
    }
}

suspend fun findActiveSessionByCode(code: String, accessToken: String): Session? {
    return lookupActiveSessionByCode(code, accessToken).session
}

suspend fun expireSession(session: Session, accessToken: String): Boolean {
    val sessionId = session.id ?: return false
    return try {
        val payload = JSONObject().put("is_active", false)
        val response = SupabaseClient.httpClient.patch("${SupabaseClient.restUrl}/sessions") {
            applyAuthenticatedHeaders(accessToken)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            parameter("id", "eq.$sessionId")
            setBody(payload.toString())
        }
        val body = response.bodyAsText()
        response.isSuccess() || missingColumnHint(body, "is_active")
    } catch (_: Exception) {
        false
    }
}

suspend fun verifyAndRecord(
    enteredCode: String,
    studentProfile: UserProfile,
    accessToken: String,
    expectedClassDate: String? = null,
    expectedClassPeriod: Int? = null,
    resolvedSession: Session? = null,
    bluetoothVerified: Boolean,
    faceVerified: Boolean,
    faceMatchScore: Double? = null,
    bluetoothRssi: Int? = null,
    bluetoothVerifiedAtMillis: Long? = null,
    faceVerifiedAtMillis: Long? = null
): AttendanceResult {
    if (studentProfile.role != UserRole.Student) {
        return AttendanceResult(false, "Only student accounts can mark attendance.")
    }
    if (!hasFaceEnrollment(studentProfile)) {
        return AttendanceResult(false, "Complete face enrollment before marking attendance.")
    }
    if (!bluetoothVerified || !faceVerified) {
        return AttendanceResult(false, "Bluetooth and live face verification must both pass before attendance can be submitted.")
    }
    if (!isVerificationFresh(bluetoothVerifiedAtMillis) || !isVerificationFresh(faceVerifiedAtMillis)) {
        return AttendanceResult(false, "Your verification checks expired. Run Bluetooth and face verification again.")
    }
    if (expectedClassDate.isNullOrBlank() || expectedClassPeriod == null) {
        return AttendanceResult(false, "Select the class date and period before marking attendance.")
    }

    val normalizedFaceScore = faceMatchScore?.toFloat()
    if (normalizedFaceScore == null || normalizedFaceScore < MIN_FACE_MATCH_SCORE) {
        return AttendanceResult(
            false,
            "Face confidence is too low for secure attendance. Capture again in better light."
        )
    }
    if (bluetoothRssi == null || bluetoothRssi < MIN_ACCEPTABLE_BLE_RSSI) {
        return AttendanceResult(
            false,
            "Teacher device signal is too weak. Move closer to the classroom device and scan again."
        )
    }

    val session = resolvedSession ?: findActiveSessionByCode(enteredCode.trim(), accessToken)
        ?: return AttendanceResult(false, "Invalid or expired attendance code.")
    if (!session.isActive) {
        return AttendanceResult(false, "This attendance session has already ended.")
    }

    val sessionDate = parseStoredLocalDate(session.classDate) ?: epochMillisToLocalDate(session.createdAt)
    val expectedDate = parseStoredLocalDate(expectedClassDate)
        ?: return AttendanceResult(false, "The selected class date is invalid.")
    if (sessionDate != expectedDate || session.classPeriod != expectedClassPeriod) {
        return AttendanceResult(
            false,
            "This code belongs to ${formatCalendarDate(sessionDate)} ${classPeriodLabel(session.classPeriod)}. Select that slot to continue."
        )
    }

    val sessionId = session.id
        ?: return AttendanceResult(false, "The matching session record has no database ID.")

    val alreadyMarked = hasStudentAlreadyMarked(accessToken, sessionId, studentProfile)
    if (alreadyMarked) {
        return AttendanceResult(false, "Attendance is already marked for this session.")
    }
    val alreadyMarkedForPeriod = hasStudentAlreadyMarkedForPeriod(
        accessToken = accessToken,
        classDate = expectedClassDate,
        classPeriod = expectedClassPeriod,
        studentProfile = studentProfile
    )
    if (alreadyMarkedForPeriod) {
        return AttendanceResult(false, "Attendance already marked for this period.")
    }

    return try {
        val record = AttendanceRecord(
            studentId = studentProfile.id,
            studentName = studentProfile.fullName,
            studentRollNumber = studentProfile.rollNumber,
            sessionId = sessionId,
            sessionCode = session.code,
            classDate = session.classDate,
            classPeriod = session.classPeriod,
            markedAt = nowMillis(),
            teacherName = session.teacherName,
            teacherId = session.teacherId
        )

        val attempts = listOf(
            JSONObject()
                .put("student_id", record.studentId)
                .put("student_name", record.studentName)
                .put("student_roll_number", record.studentRollNumber ?: JSONObject.NULL)
                .put("session_id", record.sessionId)
                .put("session_code", record.sessionCode)
                .put("class_date", record.classDate ?: JSONObject.NULL)
                .put("class_period", record.classPeriod ?: JSONObject.NULL)
                .put("teacher_id", record.teacherId ?: JSONObject.NULL)
                .put("teacher_name", record.teacherName ?: JSONObject.NULL)
                .put("marked_at", millisToIsoString(record.markedAt))
                .put("bluetooth_verified", bluetoothVerified)
                .put("face_verified", faceVerified)
                .put("face_match_score", faceMatchScore)
                .put("bluetooth_rssi", bluetoothRssi)
        )

        val result = postWithFallback(
            table = "attendance",
            payloads = attempts,
            accessToken = accessToken,
            preferRepresentation = false
        )

        if (result.response.isSuccess()) {
            AttendanceResult(true, "Attendance marked for ${studentProfile.fullName}")
        } else {
            val errorMessage = if (
                result.errorMessage.contains("duplicate", ignoreCase = true) ||
                result.errorMessage.contains("unique", ignoreCase = true)
            ) {
                "Duplicate attendance is not allowed for the same session."
            } else if (result.errorMessage.contains("bluetooth_verified", ignoreCase = true) ||
                result.errorMessage.contains("face_verified", ignoreCase = true) ||
                result.errorMessage.contains("face_match_score", ignoreCase = true) ||
                result.errorMessage.contains("bluetooth_rssi", ignoreCase = true)
            ) {
                "Attendance security columns are missing in Supabase. Run the updated supabase_setup.sql, then try again."
            } else {
                result.errorMessage
            }
            AttendanceResult(false, "Attendance could not be saved: $errorMessage")
        }
    } catch (exception: Exception) {
        AttendanceResult(false, "Attendance failed: ${exception.message ?: "unknown error"}")
    }
}

private suspend fun hasStudentAlreadyMarked(
    accessToken: String,
    sessionId: String,
    studentProfile: UserProfile
): Boolean {
    val studentId = studentProfile.id
    val requests = buildList {
        add(
        suspend {
            SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/attendance") {
                applyAuthenticatedHeaders(accessToken)
                parameter("session_id", "eq.$sessionId")
                parameter("student_id", "eq.$studentId")
                parameter("select", "id")
                parameter("limit", 1)
            }
        })
        add(suspend {
            SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/attendance") {
                applyAuthenticatedHeaders(accessToken)
                parameter("session_id", "eq.$sessionId")
                parameter("student_name", "eq.${studentProfile.fullName}")
                parameter("select", "id")
                parameter("limit", 1)
            }
        })
        studentProfile.rollNumber?.takeIf { it.isNotBlank() }?.let { rollNumber ->
            add(suspend {
                SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/attendance") {
                    applyAuthenticatedHeaders(accessToken)
                    parameter("session_id", "eq.$sessionId")
                    parameter("student_roll_number", "eq.$rollNumber")
                    parameter("select", "id")
                    parameter("limit", 1)
                }
            })
        }
    }

    return requests.any { request ->
        runCatching {
            val response = request()
            val body = response.bodyAsText()
            response.isSuccess() && JSONArray(body).length() > 0
        }.getOrDefault(false)
    }
}

private suspend fun hasStudentAlreadyMarkedForPeriod(
    accessToken: String,
    classDate: String,
    classPeriod: Int,
    studentProfile: UserProfile
): Boolean {
    val studentId = studentProfile.id
    val requests = buildList {
        add(
            suspend {
                SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/attendance") {
                    applyAuthenticatedHeaders(accessToken)
                    parameter("class_date", "eq.$classDate")
                    parameter("class_period", "eq.$classPeriod")
                    parameter("student_id", "eq.$studentId")
                    parameter("select", "id")
                    parameter("limit", 1)
                }
            }
        )
        add(
            suspend {
                SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/attendance") {
                    applyAuthenticatedHeaders(accessToken)
                    parameter("class_date", "eq.$classDate")
                    parameter("class_period", "eq.$classPeriod")
                    parameter("student_name", "eq.${studentProfile.fullName}")
                    parameter("select", "id")
                    parameter("limit", 1)
                }
            }
        )
        studentProfile.rollNumber?.takeIf { it.isNotBlank() }?.let { rollNumber ->
            add(
                suspend {
                    SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/attendance") {
                        applyAuthenticatedHeaders(accessToken)
                        parameter("class_date", "eq.$classDate")
                        parameter("class_period", "eq.$classPeriod")
                        parameter("student_roll_number", "eq.$rollNumber")
                        parameter("select", "id")
                        parameter("limit", 1)
                    }
                }
            )
        }
    }

    return requests.any { request ->
        runCatching {
            val response = request()
            val body = response.bodyAsText()
            response.isSuccess() && JSONArray(body).length() > 0
        }.getOrDefault(false)
    }
}

suspend fun loadStudentAttendance(
    accessToken: String,
    studentProfile: UserProfile
): List<AttendanceRecord> {
    val requests = listOf(
        suspend { fetchAttendanceByStudentId(accessToken, studentProfile.id, ordered = true) },
        suspend { fetchAttendanceByStudentName(accessToken, studentProfile.fullName, ordered = true) },
        suspend { fetchAttendanceByStudentId(accessToken, studentProfile.id, ordered = false) },
        suspend { fetchAttendanceByStudentName(accessToken, studentProfile.fullName, ordered = false) }
    )

    val combinedRecords = requests.flatMap { request ->
        runCatching { request() }.getOrDefault(emptyList())
    }

    return dedupeAttendanceRecords(enrichAttendanceRecords(accessToken, combinedRecords))
}

suspend fun loadTeacherAttendance(
    accessToken: String,
    teacherProfile: UserProfile
): List<AttendanceRecord> {
    val teacherSessionIds = loadTeacherSessions(accessToken, teacherProfile).mapNotNull { it.id }
    val requests = buildList {
        add(suspend { fetchAttendanceByTeacherId(accessToken, teacherProfile.id, ordered = true) })
        add(suspend { fetchAttendanceByTeacherName(accessToken, teacherProfile.fullName, ordered = true) })
        add(suspend { fetchAttendanceByTeacherId(accessToken, teacherProfile.id, ordered = false) })
        add(suspend { fetchAttendanceByTeacherName(accessToken, teacherProfile.fullName, ordered = false) })
        if (teacherSessionIds.isNotEmpty()) {
            add(suspend { fetchAttendanceBySessionIds(accessToken, teacherSessionIds, ordered = true) })
            add(suspend { fetchAttendanceBySessionIds(accessToken, teacherSessionIds, ordered = false) })
        }
    }

    val combinedRecords = requests.flatMap { request ->
        runCatching { request() }.getOrDefault(emptyList())
    }

    return dedupeAttendanceRecords(enrichAttendanceRecords(accessToken, combinedRecords))
}

private fun dedupeAttendanceRecords(records: List<AttendanceRecord>): List<AttendanceRecord> {
    return records
        .sortedByDescending { it.markedAt }
        .distinctBy { record ->
            record.id ?: listOf(
                record.studentId ?: record.studentName,
                record.sessionId,
                record.sessionCode,
                record.classDate ?: "",
                record.classPeriod?.toString() ?: "",
                record.markedAt.toString()
            ).joinToString("|")
        }
}

private suspend fun enrichAttendanceRecords(
    accessToken: String,
    records: List<AttendanceRecord>
): List<AttendanceRecord> {
    if (records.isEmpty()) return emptyList()

    val sessionsById = fetchSessionsByIds(
        accessToken = accessToken,
        sessionIds = records.mapNotNull { it.sessionId.takeIf(String::isNotBlank) }.distinct()
    ).associateBy { it.id }

    return records.map { record ->
        val linkedSession = sessionsById[record.sessionId]
        if (linkedSession == null) {
            record
        } else {
            record.copy(
                classDate = record.classDate ?: linkedSession.classDate,
                classPeriod = record.classPeriod ?: linkedSession.classPeriod,
                teacherName = record.teacherName ?: linkedSession.teacherName,
                teacherId = record.teacherId ?: linkedSession.teacherId
            )
        }
    }
}

private suspend fun fetchSessionsByIds(
    accessToken: String,
    sessionIds: List<String>
): List<Session> {
    if (sessionIds.isEmpty()) return emptyList()

    return try {
        val response = SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/sessions") {
            applyAuthenticatedHeaders(accessToken)
            parameter("id", "in.(${sessionIds.joinToString(",")})")
            parameter("select", "*")
            parameter("limit", sessionIds.size.coerceAtMost(100))
        }
        val body = response.bodyAsText()
        if (response.isSuccess()) parseSessions(body) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}

private suspend fun fetchAttendanceByStudentId(
    accessToken: String,
    studentId: String,
    ordered: Boolean
): List<AttendanceRecord> {
    val response = SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/attendance") {
        applyAuthenticatedHeaders(accessToken)
        parameter("student_id", "eq.$studentId")
        parameter("select", "*")
        if (ordered) {
            parameter("order", "marked_at.desc")
        }
        parameter("limit", 20)
    }
    return parseAttendanceResponse(response)
}

private suspend fun fetchAttendanceByStudentName(
    accessToken: String,
    studentName: String,
    ordered: Boolean
): List<AttendanceRecord> {
    val response = SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/attendance") {
        applyAuthenticatedHeaders(accessToken)
        parameter("student_name", "eq.$studentName")
        parameter("select", "*")
        if (ordered) {
            parameter("order", "marked_at.desc")
        }
        parameter("limit", 20)
    }
    return parseAttendanceResponse(response)
}

private suspend fun fetchAttendanceByTeacherId(
    accessToken: String,
    teacherId: String,
    ordered: Boolean
): List<AttendanceRecord> {
    val response = SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/attendance") {
        applyAuthenticatedHeaders(accessToken)
        parameter("teacher_id", "eq.$teacherId")
        parameter("select", "*")
        if (ordered) {
            parameter("order", "marked_at.desc")
        }
        parameter("limit", 50)
    }
    return parseAttendanceResponse(response)
}

private suspend fun fetchAttendanceByTeacherName(
    accessToken: String,
    teacherName: String,
    ordered: Boolean
): List<AttendanceRecord> {
    val response = SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/attendance") {
        applyAuthenticatedHeaders(accessToken)
        parameter("teacher_name", "eq.$teacherName")
        parameter("select", "*")
        if (ordered) {
            parameter("order", "marked_at.desc")
        }
        parameter("limit", 50)
    }
    return parseAttendanceResponse(response)
}

private suspend fun fetchAttendanceBySessionIds(
    accessToken: String,
    sessionIds: List<String>,
    ordered: Boolean
): List<AttendanceRecord> {
    val response = SupabaseClient.httpClient.get("${SupabaseClient.restUrl}/attendance") {
        applyAuthenticatedHeaders(accessToken)
        parameter("session_id", "in.(${sessionIds.joinToString(",")})")
        parameter("select", "*")
        if (ordered) {
            parameter("order", "marked_at.desc")
        }
        parameter("limit", 50)
    }
    return parseAttendanceResponse(response)
}

private suspend fun parseAttendanceResponse(response: HttpResponse): List<AttendanceRecord> {
    val body = response.bodyAsText()
    return if (response.isSuccess()) parseAttendanceRecords(body) else emptyList()
}

private fun parseProfiles(responseText: String): List<UserProfile> {
    if (responseText.isBlank()) return emptyList()
    val jsonArray = when {
        responseText.trim().startsWith("[") -> JSONArray(responseText)
        responseText.trim().startsWith("{") -> JSONArray().put(JSONObject(responseText))
        else -> JSONArray()
    }

    return List(jsonArray.length()) { index ->
        val item = jsonArray.getJSONObject(index)
        UserProfile(
            id = item.optString("id"),
            email = item.optString("email"),
            fullName = item.optString("full_name").ifBlank { item.optString("email").substringBefore("@") },
            rollNumber = item.optString("roll_number").ifBlank { null },
            role = UserRole.fromDbValue(item.optString("role")),
            createdAt = parseSupabaseTimestamp(item.opt("created_at")) ?: nowMillis(),
            faceTemplate = item.optString("face_template").ifBlank { null },
            faceEnrolledAt = parseSupabaseTimestamp(item.opt("face_enrolled_at")),
            faceImageBase64 = item.optString("face_image_base64").ifBlank { null }
        )
    }
}

private fun parseSessions(responseText: String): List<Session> {
    if (responseText.isBlank()) return emptyList()
    val jsonArray = JSONArray(responseText)
    return List(jsonArray.length()) { index ->
        val item = jsonArray.getJSONObject(index)
        val createdAt = parseSupabaseTimestamp(item.opt("created_at")) ?: nowMillis()
        val expiresAt = parseSupabaseTimestamp(item.opt("expires_at"))
            ?: (createdAt + SESSION_DURATION_MILLIS)
        Session(
            id = item.optString("id").ifBlank { null },
            code = item.optString("code"),
            teacherId = item.optString("teacher_id").ifBlank { null },
            teacherName = item.optString("teacher_name").ifBlank { "Teacher" },
            classDate = item.optString("class_date").ifBlank { null },
            classPeriod = item.optInt("class_period").takeIf { item.has("class_period") && !item.isNull("class_period") },
            createdAt = createdAt,
            expiresAt = expiresAt,
            isActive = if (item.has("is_active") && !item.isNull("is_active")) {
                item.optBoolean("is_active", true)
            } else {
                true
            }
        )
    }
}

private fun parseAttendanceRecords(responseText: String): List<AttendanceRecord> {
    if (responseText.isBlank()) return emptyList()
    val jsonArray = JSONArray(responseText)
    return List(jsonArray.length()) { index ->
        val item = jsonArray.getJSONObject(index)
        AttendanceRecord(
            id = item.optString("id").ifBlank { null },
            studentId = item.optString("student_id").ifBlank { null },
            studentName = item.optString("student_name"),
            studentRollNumber = item.optString("student_roll_number").ifBlank { null },
            sessionId = item.optString("session_id"),
            sessionCode = item.optString("session_code"),
            classDate = item.optString("class_date").ifBlank { null },
            classPeriod = item.optInt("class_period").takeIf { item.has("class_period") && !item.isNull("class_period") },
            markedAt = parseSupabaseTimestamp(item.opt("marked_at")) ?: nowMillis(),
            teacherName = item.optString("teacher_name").ifBlank { null },
            teacherId = item.optString("teacher_id").ifBlank { null },
            bluetoothVerified = item.optBoolean("bluetooth_verified", false),
            faceVerified = item.optBoolean("face_verified", false),
            faceMatchScore = item.optDouble("face_match_score").takeIf { !it.isNaN() },
            bluetoothRssi = item.optInt("bluetooth_rssi").takeIf { item.has("bluetooth_rssi") && !item.isNull("bluetooth_rssi") }
        )
    }
}

private data class PostAttemptResult(
    val response: HttpResponse,
    val body: String,
    val errorMessage: String
)

private suspend fun postWithFallback(
    table: String,
    payloads: List<JSONObject>,
    accessToken: String,
    preferRepresentation: Boolean
): PostAttemptResult {
    var lastResponse: HttpResponse? = null
    var lastBody = ""
    var lastError = "No request was sent."

    payloads.forEach { payload ->
        val response = SupabaseClient.httpClient.post("${SupabaseClient.restUrl}/$table") {
            applyAuthenticatedHeaders(accessToken)
            header(
                "Prefer",
                if (preferRepresentation) "resolution=merge-duplicates,return=representation" else "return=minimal"
            )
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val body = response.bodyAsText()

        if (response.isSuccess()) {
            return PostAttemptResult(response = response, body = body, errorMessage = "")
        }

        lastResponse = response
        lastBody = body
        lastError = extractSupabaseError(response, body)
    }

    val failedResponse = lastResponse
        ?: throw IllegalStateException("No Supabase response was received for table $table")
    return PostAttemptResult(
        response = failedResponse,
        body = lastBody,
        errorMessage = lastError
    )
}

private fun HttpResponse.isSuccess(): Boolean {
    return status.value in 200..299
}

private fun missingColumnHint(body: String, columnName: String): Boolean {
    val normalized = body.lowercase()
    return normalized.contains(columnName.lowercase()) &&
        (normalized.contains("column") || normalized.contains("schema cache"))
}

private fun extractSupabaseError(response: HttpResponse, body: String): String {
    val statusLine = "Supabase ${response.status.value} ${response.status.description}"
    return runCatching {
        val json = JSONObject(body)
        val details = listOfNotNull(
            json.optString("msg").takeIf { it.isNotBlank() },
            json.optString("message").takeIf { it.isNotBlank() },
            json.optString("error_description").takeIf { it.isNotBlank() },
            json.optString("details").takeIf { it.isNotBlank() },
            json.optString("hint").takeIf { it.isNotBlank() }
        ).joinToString(" | ")

        if (details.isNotBlank()) "$statusLine: $details" else statusLine
    }.getOrElse {
        if (body.isNotBlank()) "$statusLine: $body" else statusLine
    }
}

private fun isMissingProfilesTableError(message: String): Boolean {
    val normalized = message.lowercase()
    return normalized.contains("profiles") &&
        (normalized.contains("not found") || normalized.contains("schema cache"))
}

private fun isMissingFaceColumnsError(message: String): Boolean {
    val normalized = message.lowercase()
    return normalized.contains("face_template") ||
        normalized.contains("face_enrolled_at") ||
        normalized.contains("face_image_base64")
}

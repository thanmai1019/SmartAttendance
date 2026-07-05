package com.project.smartattendance.pro

import com.google.gson.annotations.SerializedName
import com.project.smartattendance.SupabaseClient
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Query
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.project.smartattendance.SESSION_DURATION_MILLIS

data class SupabaseSessionDto(
    @SerializedName("id") val id: String,
    @SerializedName("code") val code: String,
    @SerializedName("teacher_id") val teacherId: String? = null,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("expires_at") val expiresAt: String?,
    @SerializedName("is_active") val isActive: Boolean?,
    @SerializedName("teacher_name") val teacherName: String?
)

data class SupabaseAttendanceDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("student_name") val studentName: String,
    @SerializedName("student_roll_number") val studentId: String,
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("session_code") val sessionCode: String,
    @SerializedName("marked_at") val markedAt: String,
    @SerializedName("teacher_name") val teacherName: String? = null
)

private interface ProSupabaseApi {
    @POST("sessions")
    fun createSession(
        @HeaderMap headers: Map<String, String>,
        @Header("Prefer") prefer: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Call<List<SupabaseSessionDto>>

    @HTTP(method = "PATCH", path = "sessions", hasBody = true)
    fun endSession(
        @HeaderMap headers: Map<String, String>,
        @Header("Prefer") prefer: String,
        @Query("id") sessionIdFilter: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Call<List<SupabaseSessionDto>>

    @GET("sessions")
    fun findSessionByOtp(
        @HeaderMap headers: Map<String, String>,
        @Query("code") otpFilter: String,
        @Query("is_active") activeFilter: String,
        @Query("select") select: String,
        @Query("order") order: String,
        @Query("limit") limit: Int
    ): Call<List<SupabaseSessionDto>>

    @GET("sessions")
    fun findLatestActiveSession(
        @HeaderMap headers: Map<String, String>,
        @Query("teacher_id") teacherIdFilter: String,
        @Query("is_active") activeFilter: String,
        @Query("select") select: String,
        @Query("order") order: String,
        @Query("limit") limit: Int
    ): Call<List<SupabaseSessionDto>>

    @GET("attendance")
    fun fetchAttendance(
        @HeaderMap headers: Map<String, String>,
        @Query("session_id") sessionIdFilter: String,
        @Query("select") select: String,
        @Query("order") order: String
    ): Call<List<SupabaseAttendanceDto>>

    @GET("attendance")
    fun checkDuplicate(
        @HeaderMap headers: Map<String, String>,
        @Query("student_roll_number") studentIdFilter: String,
        @Query("session_id") sessionIdFilter: String,
        @Query("select") select: String,
        @Query("limit") limit: Int
    ): Call<List<SupabaseAttendanceDto>>

    @POST("attendance")
    fun submitAttendance(
        @HeaderMap headers: Map<String, String>,
        @Header("Prefer") prefer: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Call<List<SupabaseAttendanceDto>>
}

class ProSupabaseRepository(
    private val accessToken: String? = null
) {
    init {
        require(!accessToken.isNullOrBlank()) {
            "Authenticated access token is required for ProSupabaseRepository."
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val api: ProSupabaseApi = Retrofit.Builder()
        .baseUrl("${SupabaseClient.restUrl}/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ProSupabaseApi::class.java)

    private fun headers(usePublicToken: Boolean = false): Map<String, String> {
        val bearerToken = if (usePublicToken) {
            SupabaseClient.apiKey
        } else {
            accessToken
        }
        return mapOf(
            "apikey" to SupabaseClient.apiKey,
            "Authorization" to "Bearer $bearerToken",
            "Content-Type" to "application/json"
        )
    }

    fun createSession(
        teacherName: String,
        teacherId: String? = null,
        otp: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        onResult: (Result<SupabaseSessionDto>) -> Unit
    ) {
        val payload = mapOf(
            "id" to UUID.randomUUID().toString(),
            "code" to otp,
            "teacher_id" to teacherId,
            "teacher_name" to teacherName,
            "created_at" to millisToIso(startTimeMillis),
            "expires_at" to millisToIso(endTimeMillis),
            "is_active" to true
        )

        api.createSession(headers(), "return=representation", payload)
            .enqueue(singleItemCallback(onResult))
    }

    fun endSession(sessionId: String, onResult: (Result<Boolean>) -> Unit) {
        api.endSession(
            headers(),
            "return=representation",
            "eq.$sessionId",
            mapOf("is_active" to false)
        ).enqueue(object : Callback<List<SupabaseSessionDto>> {
            override fun onResponse(
                call: Call<List<SupabaseSessionDto>>,
                response: Response<List<SupabaseSessionDto>>
            ) {
                if (response.isSuccessful) {
                    onResult(Result.success(true))
                } else {
                    onResult(Result.failure(Exception("End session failed: ${response.code()}")))
                }
            }

            override fun onFailure(call: Call<List<SupabaseSessionDto>>, t: Throwable) {
                onResult(Result.failure(t))
            }
        })
    }

    fun findActiveSessionByOtp(otp: String, onResult: (Result<SupabaseSessionDto?>) -> Unit) {
        val normalizedOtp = otp.trim().uppercase()
        api.findSessionByOtp(
            headers(),
            "eq.$normalizedOtp",
            "eq.true",
            "*",
            "created_at.desc",
            10
        ).enqueue(object : Callback<List<SupabaseSessionDto>> {
            override fun onResponse(
                call: Call<List<SupabaseSessionDto>>,
                response: Response<List<SupabaseSessionDto>>
            ) {
                if (response.isSuccessful) {
                    val session = pickLiveSession(response.body().orEmpty())
                    if (session != null) {
                        onResult(Result.success(session))
                    } else {
                        // Fallback to public token for RLS setups where student role cannot read sessions.
                        api.findSessionByOtp(
                            headers(usePublicToken = true),
                            "eq.$normalizedOtp",
                            "eq.true",
                            "*",
                            "created_at.desc",
                            10
                        ).enqueue(object : Callback<List<SupabaseSessionDto>> {
                            override fun onResponse(
                                call: Call<List<SupabaseSessionDto>>,
                                response: Response<List<SupabaseSessionDto>>
                            ) {
                                if (response.isSuccessful) {
                                    onResult(Result.success(pickLiveSession(response.body().orEmpty())))
                                } else {
                                    onResult(Result.failure(Exception("Fetch session failed: ${response.code()}")))
                                }
                            }

                            override fun onFailure(call: Call<List<SupabaseSessionDto>>, t: Throwable) {
                                onResult(Result.failure(t))
                            }
                        })
                    }
                } else {
                    onResult(Result.failure(Exception("Fetch session failed: ${response.code()}")))
                }
            }

            override fun onFailure(call: Call<List<SupabaseSessionDto>>, t: Throwable) {
                onResult(Result.failure(t))
            }
        })
    }

    fun findLatestActiveSession(teacherId: String, onResult: (Result<SupabaseSessionDto?>) -> Unit) {
        api.findLatestActiveSession(
            headers(),
            "eq.$teacherId",
            "eq.true",
            "*",
            "created_at.desc",
            10
        ).enqueue(object : Callback<List<SupabaseSessionDto>> {
            override fun onResponse(
                call: Call<List<SupabaseSessionDto>>,
                response: Response<List<SupabaseSessionDto>>
            ) {
                if (response.isSuccessful) {
                    val now = System.currentTimeMillis()
                    val session = response.body().orEmpty().firstOrNull { dto ->
                        val expiresAt = parseIsoToMillis(dto.expiresAt) ?: 0L
                        (dto.isActive != false) && expiresAt > now
                    }
                    onResult(Result.success(session))
                } else {
                    onResult(Result.failure(Exception("Fetch active session failed: ${response.code()}")))
                }
            }

            override fun onFailure(call: Call<List<SupabaseSessionDto>>, t: Throwable) {
                onResult(Result.failure(t))
            }
        })
    }

    fun fetchAttendanceCount(sessionId: String, onResult: (Result<Int>) -> Unit) {
        api.fetchAttendance(headers(), "eq.$sessionId", "id", "marked_at.desc")
            .enqueue(object : Callback<List<SupabaseAttendanceDto>> {
                override fun onResponse(
                    call: Call<List<SupabaseAttendanceDto>>,
                    response: Response<List<SupabaseAttendanceDto>>
                ) {
                    if (response.isSuccessful) {
                        onResult(Result.success(response.body().orEmpty().size))
                    } else {
                        onResult(Result.failure(Exception("Fetch count failed: ${response.code()}")))
                    }
                }

                override fun onFailure(call: Call<List<SupabaseAttendanceDto>>, t: Throwable) {
                    onResult(Result.failure(t))
                }
            })
    }

    fun fetchAttendanceList(sessionId: String, onResult: (Result<List<SupabaseAttendanceDto>>) -> Unit) {
        api.fetchAttendance(headers(), "eq.$sessionId", "*", "marked_at.desc")
            .enqueue(object : Callback<List<SupabaseAttendanceDto>> {
                override fun onResponse(
                    call: Call<List<SupabaseAttendanceDto>>,
                    response: Response<List<SupabaseAttendanceDto>>
                ) {
                    if (response.isSuccessful) {
                        onResult(Result.success(response.body().orEmpty()))
                    } else {
                        onResult(Result.failure(Exception("Fetch list failed: ${response.code()}")))
                    }
                }

                override fun onFailure(call: Call<List<SupabaseAttendanceDto>>, t: Throwable) {
                    onResult(Result.failure(t))
                }
            })
    }

    fun checkDuplicate(studentId: String, sessionId: String, onResult: (Result<Boolean>) -> Unit) {
        api.checkDuplicate(headers(), "eq.$studentId", "eq.$sessionId", "id", 1)
            .enqueue(object : Callback<List<SupabaseAttendanceDto>> {
                override fun onResponse(
                    call: Call<List<SupabaseAttendanceDto>>,
                    response: Response<List<SupabaseAttendanceDto>>
                ) {
                    if (response.isSuccessful) {
                        onResult(Result.success(response.body().orEmpty().isNotEmpty()))
                    } else {
                        onResult(Result.failure(Exception("Check duplicate failed: ${response.code()}")))
                    }
                }

                override fun onFailure(call: Call<List<SupabaseAttendanceDto>>, t: Throwable) {
                    onResult(Result.failure(t))
                }
            })
    }

    fun submitAttendance(
        studentName: String,
        studentId: String,
        studentUuid: String? = null,
        session: SupabaseSessionDto,
        onResult: (Result<Boolean>) -> Unit
    ) {
        val payload = mapOf(
            "student_id" to studentUuid,
            "student_name" to studentName,
            "student_roll_number" to studentId,
            "session_id" to session.id,
            "session_code" to session.code,
            "teacher_id" to session.teacherId,
            "teacher_name" to (session.teacherName ?: "Teacher"),
            "marked_at" to millisToIso(System.currentTimeMillis())
        )

        api.submitAttendance(headers(), "return=representation", payload)
            .enqueue(object : Callback<List<SupabaseAttendanceDto>> {
                override fun onResponse(
                    call: Call<List<SupabaseAttendanceDto>>,
                    response: Response<List<SupabaseAttendanceDto>>
                ) {
                    if (response.isSuccessful) {
                        onResult(Result.success(true))
                    } else {
                        onResult(Result.failure(Exception("Submit failed: ${response.code()}")))
                    }
                }

                override fun onFailure(call: Call<List<SupabaseAttendanceDto>>, t: Throwable) {
                    onResult(Result.failure(t))
                }
            })
    }

    private fun singleItemCallback(onResult: (Result<SupabaseSessionDto>) -> Unit): Callback<List<SupabaseSessionDto>> {
        return object : Callback<List<SupabaseSessionDto>> {
            override fun onResponse(
                call: Call<List<SupabaseSessionDto>>,
                response: Response<List<SupabaseSessionDto>>
            ) {
                if (response.isSuccessful) {
                    val item = response.body().orEmpty().firstOrNull()
                    if (item != null) {
                        onResult(Result.success(item))
                    } else {
                        onResult(Result.failure(IllegalStateException("No record returned.")))
                    }
                } else {
                    onResult(Result.failure(Exception("Request failed: ${response.code()}")))
                }
            }

            override fun onFailure(call: Call<List<SupabaseSessionDto>>, t: Throwable) {
                onResult(Result.failure(t))
            }
        }
    }

    private fun millisToIso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun parseIsoToMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    private fun pickLiveSession(items: List<SupabaseSessionDto>): SupabaseSessionDto? {
        val now = System.currentTimeMillis()
        return items.firstOrNull { dto ->
            val createdAt = parseIsoToMillis(dto.createdAt) ?: now
            val expiresAt = parseIsoToMillis(dto.expiresAt) ?: (createdAt + SESSION_DURATION_MILLIS)
            (dto.isActive != false) && expiresAt > now
        }
    }
}

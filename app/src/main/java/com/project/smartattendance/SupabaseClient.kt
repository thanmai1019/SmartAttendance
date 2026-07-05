package com.project.smartattendance
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout

object SupabaseClient {
    private val supabaseUrl: String = normalizeSupabaseUrl(BuildConfig.SUPABASE_URL)
    private val supabaseAnonKey: String = BuildConfig.SUPABASE_ANON_KEY
    val restUrl: String = "$supabaseUrl/rest/v1"
    val authUrl: String = "$supabaseUrl/auth/v1"
    val apiKey: String = supabaseAnonKey
    val httpClient = HttpClient(Android) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
    }

    private fun normalizeSupabaseUrl(rawUrl: String): String {
        var normalized = rawUrl.trim().removeSuffix("/")
        if (normalized.endsWith("/rest/v1", ignoreCase = true)) {
            normalized = normalized.removeSuffix("/rest/v1")
        }
        if (normalized.endsWith("/auth/v1", ignoreCase = true)) {
            normalized = normalized.removeSuffix("/auth/v1")
        }
        return normalized
    }
}

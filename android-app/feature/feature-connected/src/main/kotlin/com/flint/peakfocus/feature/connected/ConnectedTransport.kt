package com.flint.peakfocus.feature.connected

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal interface ConnectedHttpTransport {
    suspend fun request(lease: ConnectedLease, method: String, path: String, body: String? = null, responseLimit: Int = 262_144): String
}

internal class AuthenticatedConnectedTransport(
    private val identity: ConnectedIdentitySession,
    private val apiBase: String,
    private val exchangeOverride: (suspend (String, String, String?, String, Int) -> Pair<Int, String>)? = null,
) : ConnectedHttpTransport {
    override suspend fun request(lease: ConnectedLease, method: String, path: String, body: String?, responseLimit: Int): String {
        for (attempt in 0..1) {
            val token = identity.token(lease, attempt == 1)
            identity.requireCurrent(lease)
            val response = try { exchangeOverride?.invoke(method, path, body, token, responseLimit)
                ?: withContext(Dispatchers.IO) { exchange(method, path, body, token, responseLimit) } }
            catch (error: IOException) {
                identity.requireCurrent(lease)
                throw ManagedRequestFailure(if (method == "GET") ManagedFailureKind.NETWORK else ManagedFailureKind.AMBIGUOUS_MUTATION,
                    if (method == "GET") "Cannot reach the local account service. Refresh when it is available."
                    else "The service may have saved this change. Refresh and review the latest policies before submitting again.")
            }
            identity.requireCurrent(lease)
            if (response.first == 401 && attempt == 0) continue // An explicit rejection occurred before any server mutation.
            if (response.first !in 200..299) {
                if (response.first == 401) identity.rejectSession(lease)
                val code = runCatching { JSONObject(response.second).optJSONObject("error")?.optString("code") }.getOrNull()
                val kind = when (response.first) {
                    401 -> ManagedFailureKind.UNAUTHENTICATED
                    403 -> ManagedFailureKind.FORBIDDEN
                    404 -> ManagedFailureKind.NOT_FOUND
                    409 -> if (code == "revision_conflict") ManagedFailureKind.REVISION_CONFLICT else ManagedFailureKind.CAPACITY
                    422 -> ManagedFailureKind.VALIDATION
                    429 -> ManagedFailureKind.RATE_LIMITED
                    else -> if (method != "GET" && response.first >= 500) ManagedFailureKind.AMBIGUOUS_MUTATION else ManagedFailureKind.NETWORK
                }
                val message = when (code) {
                    "revision_conflict" -> "This policy changed. Reload it and review your edits before saving."
                    "managed_rule_limit" -> "This group has reached its policy limit. Delete a policy before adding another."
                    else -> if (kind == ManagedFailureKind.AMBIGUOUS_MUTATION) "The service may have saved this change. Reload and review the policies before submitting again."
                        else connectedHttpError(response.first, code)
                }
                throw ManagedRequestFailure(kind, message, response.first, code)
            }
            return response.second
        }
        throw ManagedRequestFailure(ManagedFailureKind.UNAUTHENTICATED, "Your session expired. Sign in again.")
    }

    private fun exchange(method: String, path: String, body: String?, token: String, limit: Int): Pair<Int, String> {
        val connection = URL(apiBase + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000; connection.readTimeout = 12_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 8_192) { "Request body exceeds limit" }
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status in 200..299 && connection.contentType?.substringBefore(';')?.trim() != "application/json") throw IOException("Unsupported service response")
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = ByteArrayOutputStream()
            stream?.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > limit) throw IOException("Service response exceeds limit")
                    bytes.write(buffer, 0, count)
                }
            }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return status to decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } finally { connection.disconnect() }
    }
}

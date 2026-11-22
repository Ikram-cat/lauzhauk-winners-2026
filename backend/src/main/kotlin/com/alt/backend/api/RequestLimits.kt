package com.alt.backend.api

import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.serialization.json.Json

internal const val MAX_BODY_BYTES = 64 * 1024
internal const val MAX_ID_CODE_POINTS = 120
internal const val MAX_SUMMARY_CODE_POINTS = 2000

/** A 400: [field] is the offending JSON field ("body" for the whole payload). */
internal class FieldError(val field: String?, message: String) : Exception(message)

private val BODY_JSON = Json { ignoreUnknownKeys = true; isLenient = false }

/** Reads and decodes a JSON body of at most [MAX_BODY_BYTES]; anything larger or malformed is a [FieldError]. */
internal suspend inline fun <reified T> ApplicationCall.receiveBounded(): T = decodeBounded(receiveStream().readNBytes(MAX_BODY_BYTES + 1))

internal inline fun <reified T> decodeBounded(bytes: ByteArray): T {
    if (bytes.size > MAX_BODY_BYTES) throw FieldError("body", "Request body exceeds $MAX_BODY_BYTES bytes")
    return try { BODY_JSON.decodeFromString<T>(bytes.toString(Charsets.UTF_8)) } catch (e: Exception) {
        throw FieldError(null, "Malformed or incomplete JSON body")
    }
}

internal fun String?.requireWithin(field: String, max: Int) {
    if (this != null && codePointCount(0, length) > max) throw FieldError(field, "Field '$field' exceeds $max code points")
}

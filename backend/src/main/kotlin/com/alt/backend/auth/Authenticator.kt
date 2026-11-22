package com.alt.backend.auth

import com.alt.backend.service.UserRecord
import com.alt.backend.service.UserService
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall

/** Resolves the caller's user from an Authorization: Bearer header, or null. */
class Authenticator(private val tokens: TokenService, private val users: UserService) {

    suspend fun user(call: ApplicationCall): UserRecord? {
        val header = call.request.headers[HttpHeaders.Authorization]?.trim() ?: return null
        if (!header.startsWith("Bearer ", ignoreCase = true)) return null
        val raw = header.substring(7).trim()
        if (raw.isEmpty() || raw.length != 64 || !raw.all { it.isDigit() || it in 'a'..'f' }) return null
        val userId = tokens.resolve(raw) ?: return null
        return users.byId(userId)
    }

    fun rawToken(call: ApplicationCall): String? {
        val header = call.request.headers[HttpHeaders.Authorization]?.trim() ?: return null
        return header.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
    }
}

package com.alt.backend.auth

import at.favre.lib.crypto.bcrypt.BCrypt

/** BCrypt (cost 10) password hashing; hashes are ~60 characters. */
object PasswordHasher {
    fun hash(password: String): String =
        BCrypt.withDefaults().hashToString(10, password.toCharArray())

    fun verify(password: String, hash: String): Boolean =
        BCrypt.verifyer().verify(password.toByteArray(Charsets.UTF_8), hash.toByteArray(Charsets.UTF_8)).verified
}

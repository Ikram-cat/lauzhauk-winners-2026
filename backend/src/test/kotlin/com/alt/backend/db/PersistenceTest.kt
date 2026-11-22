package com.alt.backend.db

import kotlin.test.*

class PersistenceTest {
    @Test
    fun `unset DATABASE_URL gives isolated h2 memory`() {
        val c = Persistence.parseDatabaseUrl(null)
        assertTrue(c.url.startsWith("jdbc:h2:mem:alt-"))
        assertEquals("h2-in-memory", c.mode)
        assertEquals("sa", c.user)
    }

    @Test
    fun `railway postgres URL converts to jdbc`() {
        val c = Persistence.parseDatabaseUrl("postgres://u:p%40ss@host:5432/railwaydb")
        assertEquals("jdbc:postgresql://host:5432/railwaydb", c.url)
        assertEquals("u", c.user)
        assertEquals("p@ss", c.password) // URL-decoded
        assertEquals("postgres", c.mode)
    }

    @Test
    fun `postgresql prefix and query params survive`() {
        val c = Persistence.parseDatabaseUrl("postgresql://u:p@h:5432/db?sslmode=require")
        assertEquals("jdbc:postgresql://h:5432/db?sslmode=require", c.url)
    }

    @Test
    fun `jdbc URLs pass through with separate credentials`() {
        val c = Persistence.parseDatabaseUrl("jdbc:postgresql://h/db", "bob", "secret")
        assertEquals("jdbc:postgresql://h/db", c.url)
        assertEquals("bob", c.user)
        assertEquals("secret", c.password)
        assertEquals("jdbc", c.mode)
    }

    @Test
    fun `unsupported prefixes fail fast`() {
        assertFailsWith<IllegalArgumentException> { Persistence.parseDatabaseUrl("mysql://u:p@h/db") }
        assertFailsWith<IllegalArgumentException> { Persistence.parseDatabaseUrl("postgres://hostonly/db") }
    }
}

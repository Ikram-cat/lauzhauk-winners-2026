package com.alt.backend.api

import com.alt.backend.config.AppConfig
import com.alt.backend.config.configureApp
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

/**
 * Same-origin static web UI (WEB_DIR): index and assets served at "/", the /api routes untouched,
 * and NO static routes at all when the directory is missing (byte-identical behavior).
 */
class StaticUiTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC)

    private fun webFixture(): java.io.File {
        val dir = Files.createTempDirectory("alt-web").toFile()
        dir.resolve("index.html").writeText("<html><body>alt-ui</body></html>")
        dir.resolve("style.css").writeText("body{}")
        dir.resolve("app.mjs").writeText("export const x = 1;")
        return dir
    }

    @Test
    fun `static ui served at root and api routes still win`() = testApplication {
        val web = webFixture()
        application { configureApp(clock, AppConfig(transportProvider = "mock", fareBaseUrl = "", webDir = web.absolutePath)) }
        val root = client.get("/")
        assertEquals(HttpStatusCode.OK, root.status)
        assertEquals(ContentType.Text.Html.withCharset(Charsets.UTF_8), root.contentType())
        assertTrue(root.bodyAsText().contains("alt-ui"))
        val css = client.get("/style.css")
        assertEquals(HttpStatusCode.OK, css.status)
        assertTrue(css.contentType().toString().contains("css", ignoreCase = true))
        assertEquals(HttpStatusCode.OK, client.get("/app.mjs").status)
        val health = client.get("/api/../health") // route precedence is by specificity, not path tricks
        val direct = client.get("/health")
        assertEquals(HttpStatusCode.OK, direct.status)
        assertTrue(direct.bodyAsText().contains("\"status\""))
        assertEquals(HttpStatusCode.OK, client.get("/api/locations?query=x").status)
    }

    @Test
    fun `missing web dir means no static routes at all`() = testApplication {
        application { configureApp(clock, AppConfig(transportProvider = "mock", fareBaseUrl = "", webDir = "/nonexistent-alt-web")) }
        assertEquals(HttpStatusCode.NotFound, client.get("/").status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }
}

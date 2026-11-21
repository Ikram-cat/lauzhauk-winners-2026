package com.cffplanb.backend

import com.cffplanb.backend.config.configureApp
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.slf4j.LoggerFactory
import java.time.Clock

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    LoggerFactory.getLogger("com.cffplanb.backend.Application").info("Starting backend on port {}", port)
    embeddedServer(Netty, host = "0.0.0.0", port = port) {
        configureApp(Clock.systemDefaultZone())
    }.start(wait = true)
}

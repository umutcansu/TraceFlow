package io.github.umutcansu.traceflow.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CopyOnWriteArrayList

@Serializable
data class TraceEvent(
    val type: String,
    val `class`: String = "",
    val method: String = "",
    val file: String = "",
    val line: Int = 0,
    val threadId: Long = 0,
    val threadName: String = "",
    val ts: Long = 0,
    val deviceModel: String = "",
    val tag: String = "",
    val params: Map<String, String>? = null,
    val result: String? = null,
    val durationMs: Long? = null,
    val exception: String? = null,
    val message: String? = null,
    val tryStartLine: Int? = null,
    val conditionResult: Boolean? = null,
)

@Serializable
data class StatsResponse(val totalEvents: Int, val devices: Map<String, Int>, val types: Map<String, Int>)

@Serializable
data class HealthResponse(val service: String, val events: Int)

@Serializable
data class SimpleResponse(val message: String)

fun TraceEvent.deviceLabel(): String = when {
    tag.isNotEmpty() && deviceModel.isNotEmpty() -> "$deviceModel ($tag)"
    tag.isNotEmpty() -> tag
    deviceModel.isNotEmpty() -> deviceModel
    else -> ""
}

val events = CopyOnWriteArrayList<TraceEvent>()

val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 4567
    println("TraceFlow Sample Server starting on port $port...")

    embeddedServer(Netty, port = port) {
        install(ContentNegotiation) {
            json(json)
        }
        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
        }
        routing {
            // POST /traces — receive batch of events from devices
            post("/traces") {
                val batch = call.receive<List<TraceEvent>>()
                events.addAll(batch)
                val devices = batch.map { it.tag.ifEmpty { it.deviceModel } }.distinct()
                println("[+] Received ${batch.size} events from: ${devices.joinToString()}")
                call.respond(HttpStatusCode.OK, SimpleResponse("received ${batch.size} events"))
            }

            // GET /traces?since={ts} — return events after timestamp
            get("/traces") {
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                val filtered = events.filter { it.ts > since }
                call.respond(filtered)
            }

            // GET /stats — overview
            get("/stats") {
                call.respond(StatsResponse(
                    totalEvents = events.size,
                    devices = events.map { it.deviceLabel() }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount(),
                    types = events.groupingBy { it.type }.eachCount(),
                ))
            }

            // DELETE /traces — clear all events
            delete("/traces") {
                val count = events.size
                events.clear()
                println("[x] Cleared $count events")
                call.respond(SimpleResponse("cleared $count events"))
            }

            // GET / — health check
            get("/") {
                call.respond(HealthResponse(
                    service = "TraceFlow Sample Server",
                    events = events.size,
                ))
            }
        }
    }.start(wait = true)
}

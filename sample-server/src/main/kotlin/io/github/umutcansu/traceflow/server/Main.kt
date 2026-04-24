package io.github.umutcansu.traceflow.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.utils.io.jvm.javaio.*
import java.util.zip.GZIPInputStream
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

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
    val deviceManufacturer: String = "",
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

// -- SQLite table definition --------------------------------------------------

object TraceEvents : Table("trace_events") {
    val id = integer("id").autoIncrement()
    val type = varchar("type", 10)
    val className = varchar("class_name", 500)
    val method = varchar("method", 200)
    val file = varchar("file", 300)
    val line = integer("line")
    val threadId = long("thread_id")
    val threadName = varchar("thread_name", 200)
    val ts = long("ts")
    val deviceManufacturer = varchar("device_manufacturer", 100)
    val deviceModel = varchar("device_model", 100)
    val tag = varchar("tag", 200)
    val params = text("params").nullable()
    val result = text("result").nullable()
    val durationMs = long("duration_ms").nullable()
    val exception = text("exception").nullable()
    val message = text("message").nullable()
    val tryStartLine = integer("try_start_line").nullable()
    val conditionResult = bool("condition_result").nullable()

    override val primaryKey = PrimaryKey(id)
}

// -- Database helpers ---------------------------------------------------------

val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun initDatabase(dbPath: String) {
    Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
    transaction {
        SchemaUtils.create(TraceEvents)
    }
    println("Database initialized: $dbPath")
}

fun insertEvents(batch: List<TraceEvent>) {
    transaction {
        TraceEvents.batchInsert(batch) { event ->
            this[TraceEvents.type] = event.type
            this[TraceEvents.className] = event.`class`
            this[TraceEvents.method] = event.method
            this[TraceEvents.file] = event.file
            this[TraceEvents.line] = event.line
            this[TraceEvents.threadId] = event.threadId
            this[TraceEvents.threadName] = event.threadName
            this[TraceEvents.ts] = event.ts
            this[TraceEvents.deviceManufacturer] = event.deviceManufacturer
            this[TraceEvents.deviceModel] = event.deviceModel
            this[TraceEvents.tag] = event.tag
            this[TraceEvents.params] = event.params?.let { Json.encodeToString(it) }
            this[TraceEvents.result] = event.result
            this[TraceEvents.durationMs] = event.durationMs
            this[TraceEvents.exception] = event.exception
            this[TraceEvents.message] = event.message
            this[TraceEvents.tryStartLine] = event.tryStartLine
            this[TraceEvents.conditionResult] = event.conditionResult
        }
    }
}

fun queryEvents(since: Long): List<TraceEvent> = transaction {
    TraceEvents.selectAll()
        .where { TraceEvents.ts greater since }
        .orderBy(TraceEvents.ts)
        .map { row ->
            TraceEvent(
                type = row[TraceEvents.type],
                `class` = row[TraceEvents.className],
                method = row[TraceEvents.method],
                file = row[TraceEvents.file],
                line = row[TraceEvents.line],
                threadId = row[TraceEvents.threadId],
                threadName = row[TraceEvents.threadName],
                ts = row[TraceEvents.ts],
                deviceManufacturer = row[TraceEvents.deviceManufacturer],
                deviceModel = row[TraceEvents.deviceModel],
                tag = row[TraceEvents.tag],
                params = row[TraceEvents.params]?.let { Json.decodeFromString(it) },
                result = row[TraceEvents.result],
                durationMs = row[TraceEvents.durationMs],
                exception = row[TraceEvents.exception],
                message = row[TraceEvents.message],
                tryStartLine = row[TraceEvents.tryStartLine],
                conditionResult = row[TraceEvents.conditionResult],
            )
        }
}

fun countEvents(): Int = transaction {
    TraceEvents.selectAll().count().toInt()
}

fun clearEvents(): Int = transaction {
    val count = TraceEvents.selectAll().count().toInt()
    TraceEvents.deleteAll()
    count
}

fun statsQuery(): StatsResponse = transaction {
    val total = TraceEvents.selectAll().count().toInt()
    val devices = TraceEvents.select(TraceEvents.deviceModel, TraceEvents.tag)
        .groupBy(TraceEvents.deviceModel, TraceEvents.tag)
        .associate { row ->
            val label = when {
                row[TraceEvents.tag].isNotEmpty() && row[TraceEvents.deviceModel].isNotEmpty() ->
                    "${row[TraceEvents.deviceModel]} (${row[TraceEvents.tag]})"
                row[TraceEvents.tag].isNotEmpty() -> row[TraceEvents.tag]
                row[TraceEvents.deviceModel].isNotEmpty() -> row[TraceEvents.deviceModel]
                else -> "(unknown)"
            } to 0
            label
        }
    // Recount properly
    val deviceCounts = mutableMapOf<String, Int>()
    TraceEvents.selectAll().forEach { row ->
        val label = when {
            row[TraceEvents.tag].isNotEmpty() && row[TraceEvents.deviceModel].isNotEmpty() ->
                "${row[TraceEvents.deviceModel]} (${row[TraceEvents.tag]})"
            row[TraceEvents.tag].isNotEmpty() -> row[TraceEvents.tag]
            row[TraceEvents.deviceModel].isNotEmpty() -> row[TraceEvents.deviceModel]
            else -> "(unknown)"
        }
        deviceCounts[label] = (deviceCounts[label] ?: 0) + 1
    }
    val typeCounts = mutableMapOf<String, Int>()
    TraceEvents.selectAll().forEach { row ->
        val t = row[TraceEvents.type]
        typeCounts[t] = (typeCounts[t] ?: 0) + 1
    }
    StatsResponse(totalEvents = total, devices = deviceCounts, types = typeCounts)
}

// -- Main ---------------------------------------------------------------------

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 4567
    val dbPath = System.getenv("DB_PATH") ?: "traceflow.db"

    println("TraceFlow Sample Server starting on port $port...")
    initDatabase(dbPath)

    embeddedServer(Netty, port = port) {
        install(Compression) {
            gzip()
            deflate()
        }
        install(ContentNegotiation) {
            json(json)
        }
        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
        }
        routing {
            // POST /traces — receive batch of events from devices
            post("/traces") {
                val encoding = call.request.headers["Content-Encoding"].orEmpty()
                val batch: List<TraceEvent> = if (encoding.contains("gzip", ignoreCase = true)) {
                    val text = GZIPInputStream(call.receiveStream())
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    json.decodeFromString(text)
                } else {
                    call.receive()
                }
                insertEvents(batch)
                val devices = batch.map { it.tag.ifEmpty { it.deviceModel } }.distinct()
                println("[+] Received ${batch.size} events from: ${devices.joinToString()}")
                call.respond(HttpStatusCode.OK, SimpleResponse("received ${batch.size} events"))
            }

            // GET /traces?since={ts} — return events after timestamp
            get("/traces") {
                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                val filtered = queryEvents(since)
                call.respond(filtered)
            }

            // GET /stats — overview
            get("/stats") {
                call.respond(statsQuery())
            }

            // DELETE /traces — clear all events
            delete("/traces") {
                val count = clearEvents()
                println("[x] Cleared $count events")
                call.respond(SimpleResponse("cleared $count events"))
            }

            // GET / — health check
            get("/") {
                call.respond(HealthResponse(
                    service = "TraceFlow Sample Server",
                    events = countEvents(),
                ))
            }
        }
    }.start(wait = true)
}

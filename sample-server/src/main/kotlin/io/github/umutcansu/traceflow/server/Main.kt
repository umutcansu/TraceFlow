package io.github.umutcansu.traceflow.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.utils.io.jvm.javaio.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
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
    // -- Schema v2 optional fields (all nullable; omitted by v1 clients) --
    val schemaVersion: Int? = null,
    val platform: String? = null,       // android-jvm | react-native | ios-swift | web-js
    val runtime: String? = null,        // e.g. "jvm-17", "hermes-0.12.0"
    val appId: String? = null,
    val appVersion: String? = null,
    val buildNumber: String? = null,
    val userId: String? = null,
    val deviceId: String? = null,       // anonymous UUID, persisted client-side
    val sessionId: String? = null,
    val stack: List<String>? = null,    // structured stack (JS exceptions)
    val sourceMapId: String? = null,    // JS bundle hash for stack demap
    val proguardMapId: String? = null,  // Android R8/ProGuard mapping id
    val isMinified: Boolean? = null,
)

@Serializable
data class StatsResponse(val totalEvents: Int, val devices: Map<String, Int>, val types: Map<String, Int>)

@Serializable
data class HealthResponse(val service: String, val events: Int)

@Serializable
data class SimpleResponse(val message: String)

@Serializable
data class TracesPage(val events: List<TraceEvent>, val nextCursor: Long? = null)

@Serializable
data class AppSummary(
    val appId: String,
    val eventCount: Int,
    val lastSeen: Long,
    val platforms: List<String>,
)

data class TraceQuery(
    val since: Long = 0L,
    val until: Long? = null,
    val platform: String? = null,
    val appId: String? = null,
    val userId: String? = null,
    val deviceId: String? = null,
    val level: String? = null,   // ENTER | EXIT | CATCH | BRANCH
    val tag: String? = null,
    val limit: Int = 500,
)

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

    // -- Schema v2 columns (nullable; v1 clients leave empty) --
    val schemaVersion = integer("schema_version").nullable()
    val platform = varchar("platform", 32).nullable()
    val runtime = varchar("runtime", 64).nullable()
    val appId = varchar("app_id", 200).nullable()
    val appVersion = varchar("app_version", 64).nullable()
    val buildNumber = varchar("build_number", 64).nullable()
    val userId = varchar("user_id", 200).nullable()
    val deviceId = varchar("device_id", 64).nullable()
    val sessionId = varchar("session_id", 64).nullable()
    val stack = text("stack").nullable()              // serialized JSON array of frames
    val sourceMapId = varchar("source_map_id", 128).nullable()
    val proguardMapId = varchar("proguard_map_id", 128).nullable()
    val isMinified = bool("is_minified").nullable()

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
        // Safe migration: adds any missing v2 columns to existing DBs without data loss.
        SchemaUtils.createMissingTablesAndColumns(TraceEvents)
        // Indexes for filter/pagination performance (SQLite: CREATE INDEX IF NOT EXISTS).
        exec("CREATE INDEX IF NOT EXISTS idx_events_ts ON trace_events(ts)")
        exec("CREATE INDEX IF NOT EXISTS idx_events_app_ts ON trace_events(app_id, ts)")
        exec("CREATE INDEX IF NOT EXISTS idx_events_platform_ts ON trace_events(platform, ts)")
        exec("CREATE INDEX IF NOT EXISTS idx_events_user_ts ON trace_events(user_id, ts)")
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
            this[TraceEvents.schemaVersion] = event.schemaVersion
            this[TraceEvents.platform] = event.platform
            this[TraceEvents.runtime] = event.runtime
            this[TraceEvents.appId] = event.appId
            this[TraceEvents.appVersion] = event.appVersion
            this[TraceEvents.buildNumber] = event.buildNumber
            this[TraceEvents.userId] = event.userId
            this[TraceEvents.deviceId] = event.deviceId
            this[TraceEvents.sessionId] = event.sessionId
            this[TraceEvents.stack] = event.stack?.let { Json.encodeToString(it) }
            this[TraceEvents.sourceMapId] = event.sourceMapId
            this[TraceEvents.proguardMapId] = event.proguardMapId
            this[TraceEvents.isMinified] = event.isMinified
        }
    }
}

private fun rowToEvent(row: ResultRow): TraceEvent = TraceEvent(
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
    schemaVersion = row[TraceEvents.schemaVersion],
    platform = row[TraceEvents.platform],
    runtime = row[TraceEvents.runtime],
    appId = row[TraceEvents.appId],
    appVersion = row[TraceEvents.appVersion],
    buildNumber = row[TraceEvents.buildNumber],
    userId = row[TraceEvents.userId],
    deviceId = row[TraceEvents.deviceId],
    sessionId = row[TraceEvents.sessionId],
    stack = row[TraceEvents.stack]?.let { Json.decodeFromString(it) },
    sourceMapId = row[TraceEvents.sourceMapId],
    proguardMapId = row[TraceEvents.proguardMapId],
    isMinified = row[TraceEvents.isMinified],
)

fun queryEvents(q: TraceQuery): TracesPage = transaction {
    val limit = q.limit.coerceIn(1, 5000)
    var query = TraceEvents.selectAll().where { TraceEvents.ts greater q.since }
    q.until?.let    { v -> query = query.andWhere { TraceEvents.ts lessEq v } }
    q.platform?.let { v -> query = query.andWhere { TraceEvents.platform eq v } }
    q.appId?.let    { v -> query = query.andWhere { TraceEvents.appId eq v } }
    q.userId?.let   { v -> query = query.andWhere { TraceEvents.userId eq v } }
    q.deviceId?.let { v -> query = query.andWhere { TraceEvents.deviceId eq v } }
    q.level?.let    { v -> query = query.andWhere { TraceEvents.type eq v } }
    q.tag?.let      { v -> query = query.andWhere { TraceEvents.tag eq v } }

    val rows = query.orderBy(TraceEvents.ts)
        .limit(limit + 1)   // probe for "hasMore"
        .map(::rowToEvent)

    val hasMore = rows.size > limit
    val page = if (hasMore) rows.take(limit) else rows
    TracesPage(events = page, nextCursor = if (hasMore) page.last().ts else null)
}

fun countEvents(): Int = transaction {
    TraceEvents.selectAll().count().toInt()
}

fun clearEvents(): Int = transaction {
    val count = TraceEvents.selectAll().count().toInt()
    TraceEvents.deleteAll()
    count
}

/**
 * GDPR right-to-erasure (Art. 17): remove every event tied to a userId.
 * Returns the row count that was deleted so callers can report back.
 */
fun deleteByUserId(userId: String): Int = transaction {
    TraceEvents.deleteWhere { TraceEvents.userId eq userId }
}

/**
 * Summarizes every app that has sent at least one schema-v2 event
 * (i.e. with a non-null app_id). v1 legacy rows are intentionally
 * excluded so the plugin's app picker only surfaces identifiable apps.
 *
 * Returns one entry per distinct appId with event count, last-seen
 * timestamp, and the distinct set of platforms that appId has been
 * seen from. Sorted by lastSeen desc so the most-active app is first.
 */
fun listApps(): List<AppSummary> = transaction {
    val counts = mutableMapOf<String, Int>()
    val lastSeen = mutableMapOf<String, Long>()
    val platforms = mutableMapOf<String, MutableSet<String>>()

    TraceEvents
        .select(TraceEvents.appId, TraceEvents.platform, TraceEvents.ts)
        .where { TraceEvents.appId.isNotNull() }
        .forEach { row ->
            val appId = row[TraceEvents.appId] ?: return@forEach
            counts[appId] = (counts[appId] ?: 0) + 1
            val ts = row[TraceEvents.ts]
            if (ts > (lastSeen[appId] ?: Long.MIN_VALUE)) lastSeen[appId] = ts
            row[TraceEvents.platform]?.let { p ->
                platforms.getOrPut(appId) { mutableSetOf() }.add(p)
            }
        }

    counts.keys.map { appId ->
        AppSummary(
            appId = appId,
            eventCount = counts.getValue(appId),
            lastSeen = lastSeen[appId] ?: 0L,
            platforms = platforms[appId]?.toList()?.sorted() ?: emptyList(),
        )
    }.sortedByDescending { it.lastSeen }
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

    // Log the effective auth posture so operators don't wonder why
    // production deployments are accepting anonymous traffic.
    println("Auth: ingest=${if (AuthConfig.ingestEnabled) "shared-token" else "open"}, " +
        "admin=${if (AuthConfig.adminEnabled) "JWT" else "open"}")

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
            allowHeader("X-TraceFlow-Token")
        }
        installTraceFlowRateLimits()
        installTraceFlowAuth()

        routing {
            // POST /traces — ingest. Rate-limited by both IP and token so a
            // single device can't DoS the server, and a single token can't
            // overwhelm it either. Wrapping is nested for AND semantics.
            rateLimit(RateLimitName("ingest-ip")) {
                rateLimit(RateLimitName("ingest-token")) {
                    post("/traces") {
                        if (!call.requireIngestToken()) return@post
                        val encoding = call.request.headers["Content-Encoding"].orEmpty()
                        val batch: List<TraceEvent> = try {
                            // Buffer the body so we can both honour Content-Encoding AND
                            // fall back to gzip-magic detection for runtimes that gzip the
                            // payload but lose the header in transit (observed with the JS
                            // runtime on RN/Hermes — the header is set on the fetch but
                            // doesn't survive the platform's HTTP layer).
                            val raw = SizeLimitedInputStream(
                                call.receiveStream(), MAX_COMPRESSED_BYTES, "compressed body"
                            )
                            val rawBytes = raw.readBytes()
                            val isGzipHeader = encoding.contains("gzip", ignoreCase = true)
                            val isGzipMagic = rawBytes.size >= 2 &&
                                rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()
                            val textStream = if (isGzipHeader || isGzipMagic) {
                                // And a second cap on the decompressed side defeats
                                // zip-bomb ratios where a tiny gzip expands to gigabytes.
                                SizeLimitedInputStream(
                                    GZIPInputStream(rawBytes.inputStream()),
                                    MAX_DECOMPRESSED_BYTES,
                                    "decompressed body"
                                )
                            } else {
                                rawBytes.inputStream()
                            }
                            val text = textStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            json.decodeFromString(text)
                        } catch (e: PayloadTooLargeException) {
                            println("[!] Rejected oversize request: ${e.message}")
                            call.respond(HttpStatusCode.PayloadTooLarge, SimpleResponse(e.message ?: "payload too large"))
                            return@post
                        }
                        insertEvents(batch)
                        val devices = batch.map { it.tag.ifEmpty { it.deviceModel } }.distinct()
                        println("[+] Received ${batch.size} events from: ${devices.joinToString()}")
                        call.respond(HttpStatusCode.OK, SimpleResponse("received ${batch.size} events"))
                    }
                }
            }

            // Admin routes — JWT-gated when TRACEFLOW_JWT_SECRET is set,
            // open otherwise. Keeping the route definitions in a shared
            // block avoids duplicating them in both branches.
            if (AuthConfig.adminEnabled) {
                authenticate("admin") { adminRoutes() }
            } else {
                adminRoutes()
            }

            // Health check is always open so load-balancers don't need tokens.
            get("/") {
                call.respond(HealthResponse(
                    service = "TraceFlow Sample Server",
                    events = countEvents(),
                ))
            }
        }
    }.start(wait = true)
}

/**
 * Routes that require admin privileges when TRACEFLOW_JWT_SECRET is set.
 * `DELETE /traces?userId=<id>` implements GDPR Art. 17 (right-to-erasure);
 * without the query param it clears everything (useful during dev).
 */
private fun Route.adminRoutes() {
    get("/traces") {
        val p = call.request.queryParameters
        val q = TraceQuery(
            since    = p["since"]?.toLongOrNull() ?: 0L,
            until    = p["until"]?.toLongOrNull(),
            platform = p["platform"],
            appId    = p["appId"],
            userId   = p["userId"],
            deviceId = p["deviceId"],
            level    = p["level"],
            tag      = p["tag"],
            limit    = p["limit"]?.toIntOrNull() ?: 500,
        )
        call.respond(queryEvents(q))
    }

    get("/stats") { call.respond(statsQuery()) }

    get("/apps") { call.respond(listApps()) }

    delete("/traces") {
        val userId = call.request.queryParameters["userId"]
        if (userId != null) {
            val deleted = deleteByUserId(userId)
            println("[x] GDPR delete: removed $deleted events for userId=$userId")
            call.respond(SimpleResponse("deleted $deleted events for userId=$userId"))
        } else {
            val count = clearEvents()
            println("[x] Cleared $count events")
            call.respond(SimpleResponse("cleared $count events"))
        }
    }
}

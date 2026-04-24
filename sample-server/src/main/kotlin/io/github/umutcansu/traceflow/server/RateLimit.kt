package io.github.umutcansu.traceflow.server

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.origin
import kotlin.time.Duration.Companion.seconds

/**
 * Per-IP and per-token rate-limit buckets. Applied to POST /traces (ingest)
 * and optionally to admin routes. The numbers are starting points — calibrate
 * once we have real client × event/sec measurements.
 *
 * - ingest-ip:    600 req/min per IP (defence against runaway batching).
 * - ingest-token: 10k req/min per ingest token (higher — a real app with
 *                 many devices will legitimately exceed the IP bucket).
 * - admin-ip:     120 req/min per IP (GETs are cheap; dashboards cache).
 *
 * Exceeded buckets return 429 automatically.
 */
fun Application.installTraceFlowRateLimits() {
    install(RateLimit) {
        register(RateLimitName("ingest-ip")) {
            rateLimiter(limit = 600, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
        register(RateLimitName("ingest-token")) {
            rateLimiter(limit = 10_000, refillPeriod = 60.seconds)
            requestKey { call -> call.request.headers["X-TraceFlow-Token"] ?: "anon" }
        }
        register(RateLimitName("admin-ip")) {
            rateLimiter(limit = 120, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }
}

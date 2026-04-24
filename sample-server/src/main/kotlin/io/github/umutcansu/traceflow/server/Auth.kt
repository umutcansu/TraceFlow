package io.github.umutcansu.traceflow.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.request.header
import io.ktor.server.response.respond
import java.security.MessageDigest

/**
 * Auth configuration is opt-in, driven entirely by environment variables so
 * the sample-server keeps running auth-less out of the box for local demos.
 *
 * - TRACEFLOW_INGEST_TOKEN:  when set, POST /traces requires
 *   X-TraceFlow-Token: <value>. Token comparison is constant-time.
 * - TRACEFLOW_JWT_SECRET:    when set, admin endpoints
 *   (GET /traces, GET /apps, GET /stats, DELETE /traces) require a
 *   valid Bearer JWT (HMAC256) with a matching audience.
 * - TRACEFLOW_JWT_ISSUER / TRACEFLOW_JWT_AUDIENCE: optional, default
 *   to "traceflow" / "traceflow-admin".
 *
 * Unset env vars mean "disabled" — the routes stay fully open.
 */
object AuthConfig {
    val ingestToken: String? = System.getenv("TRACEFLOW_INGEST_TOKEN")?.takeIf { it.isNotBlank() }
    val jwtSecret: String? = System.getenv("TRACEFLOW_JWT_SECRET")?.takeIf { it.isNotBlank() }
    val jwtIssuer: String = System.getenv("TRACEFLOW_JWT_ISSUER") ?: "traceflow"
    val jwtAudience: String = System.getenv("TRACEFLOW_JWT_AUDIENCE") ?: "traceflow-admin"

    val ingestEnabled: Boolean get() = ingestToken != null
    val adminEnabled: Boolean get() = jwtSecret != null
}

/**
 * Install JWT authentication when an admin secret is configured. When
 * TRACEFLOW_JWT_SECRET is unset this is a no-op — callers must guard
 * admin routes with `if (AuthConfig.adminEnabled) authenticate("admin") { ... }`.
 */
fun Application.installTraceFlowAuth() {
    val secret = AuthConfig.jwtSecret ?: return
    install(Authentication) {
        jwt("admin") {
            realm = "traceflow-admin"
            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .withIssuer(AuthConfig.jwtIssuer)
                    .withAudience(AuthConfig.jwtAudience)
                    .build()
            )
            validate { cred ->
                if (cred.payload.audience.contains(AuthConfig.jwtAudience)) JWTPrincipal(cred.payload)
                else null
            }
        }
    }
}

/**
 * Gate POST /traces on a shared ingest token when TRACEFLOW_INGEST_TOKEN is set.
 * Returns true if the request should proceed, false if the handler should
 * bail out because we already responded with 401.
 *
 * Comparison is constant-time so attackers can't learn the token one byte at
 * a time from response timing.
 */
suspend fun ApplicationCall.requireIngestToken(): Boolean {
    val expected = AuthConfig.ingestToken ?: return true
    val provided = request.header("X-TraceFlow-Token").orEmpty()
    val expectedBytes = expected.toByteArray(Charsets.UTF_8)
    val providedBytes = provided.toByteArray(Charsets.UTF_8)
    if (expectedBytes.size != providedBytes.size ||
        !MessageDigest.isEqual(expectedBytes, providedBytes)
    ) {
        respond(HttpStatusCode.Unauthorized, SimpleResponse("invalid or missing X-TraceFlow-Token"))
        return false
    }
    return true
}

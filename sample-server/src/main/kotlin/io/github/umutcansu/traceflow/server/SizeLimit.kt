package io.github.umutcansu.traceflow.server

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Thrown when a request body exceeds the configured size limit. Callers
 * should translate this into a 413 Payload Too Large response.
 */
class PayloadTooLargeException(message: String) : IOException(message)

/**
 * InputStream wrapper that enforces a hard byte-count ceiling. Used to
 * defend against zip-bomb / oversize-body DoS:
 *
 *   - the raw request body is wrapped with the compressed cap (a kilobyte
 *     gzip expanding to gigabytes of JSON is cheap to send),
 *   - the decompressed stream is wrapped again with the expanded cap,
 *     so a legitimate-looking compression ratio can't hide a bomb.
 *
 * Limits are absolute; once the count crosses the threshold the next
 * read throws. The wrapped stream is still closed normally by the caller.
 */
class SizeLimitedInputStream(
    delegate: InputStream,
    private val maxBytes: Long,
    private val label: String,
) : FilterInputStream(delegate) {

    private var count: Long = 0

    override fun read(): Int {
        val b = super.read()
        if (b != -1) {
            count++
            enforce()
        }
        return b
    }

    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        val n = super.read(buf, off, len)
        if (n > 0) {
            count += n
            enforce()
        }
        return n
    }

    private fun enforce() {
        if (count > maxBytes) {
            throw PayloadTooLargeException(
                "$label exceeded $maxBytes bytes"
            )
        }
    }
}

/** Default caps for POST /traces. Small enough to block abuse, big enough for real batches. */
const val MAX_COMPRESSED_BYTES: Long = 5L * 1024 * 1024        // 5 MB on the wire
const val MAX_DECOMPRESSED_BYTES: Long = 50L * 1024 * 1024     // 50 MB after gunzip

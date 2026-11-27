package com.music.myusiq.utils

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Minimal ListenBrainz scrobbling client (https://listenbrainz.org).
 * Submits "single" listens with track/artist/album metadata; the user's
 * token comes from their ListenBrainz profile page. Fire-and-forget with
 * a small in-memory retry queue: failed submits are re-attempted before
 * the next successful one (bounded, oldest dropped).
 */
object ListenBrainzClient {

    private const val SUBMIT_URL = "https://api.listenbrainz.org/1/submit-listens"
    private const val VALIDATE_URL = "https://api.listenbrainz.org/1/validate-token?token="
    private const val MAX_QUEUE = 50

    private val pendingQueue = ArrayDeque<String>()

    /**
     * Queues a listen. JSON payloads are built here so the queue survives
     * even when the network call fails (listened_at stays accurate).
     */
    fun enqueue(
        token: String,
        trackName: String,
        artistName: String,
        albumName: String?,
        durationMs: Long,
        listenedAtUnix: Long
    ) {
        if (token.isBlank() || trackName.isBlank() || artistName.isBlank()) return
        val payload = JSONObject().apply {
            put("listen_type", "single")
            put("payload", JSONArray().put(JSONObject().apply {
                put("listened_at", listenedAtUnix)
                put("track_metadata", JSONObject().apply {
                    put("track_name", trackName)
                    put("artist_name", artistName)
                    if (!albumName.isNullOrBlank()) put("album_name", albumName)
                    put("additional_info", JSONObject().apply {
                        if (durationMs > 0) put("duration_ms", durationMs)
                        put("submission_client", "Myusiq")
                    })
                })
            }))
        }
        synchronized(pendingQueue) {
            pendingQueue.addLast(payload.toString())
            while (pendingQueue.size > MAX_QUEUE) pendingQueue.removeFirst()
        }
    }

    /** Flushes the queue (oldest first); stops at the first network failure. */
    fun flush(token: String): Int {
        var sent = 0
        while (true) {
            val body = synchronized(pendingQueue) { pendingQueue.firstOrNull() } ?: return sent
            val ok = post(token, body)
            if (ok) {
                synchronized(pendingQueue) { pendingQueue.removeFirst() }
                sent++
            } else {
                return sent
            }
        }
    }

    /** Validates a token against ListenBrainz; returns true on HTTP 200. */
    fun validateToken(token: String): Boolean = try {
        val connection = URL(VALIDATE_URL + token).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val ok = connection.responseCode == HttpURLConnection.HTTP_OK
        connection.disconnect()
        ok
    } catch (_: Exception) {
        false
    }

    private fun post(token: String, jsonBody: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(SUBMIT_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Token $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(jsonBody.toByteArray(StandardCharsets.UTF_8))
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val err = try {
                    connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                } catch (_: Exception) { null }
                Log.w("ListenBrainz", "Submit failed: HTTP $code ${err?.take(200)}")
            }
            code in 200..299
        } catch (e: Exception) {
            Log.w("ListenBrainz", "Submit error: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }
}

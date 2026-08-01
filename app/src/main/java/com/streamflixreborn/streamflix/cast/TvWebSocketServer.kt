package com.streamflixreborn.streamflix.cast

import android.util.Log
import com.google.gson.Gson
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class TvWebSocketServer(
    port: Int = 8080,
    private val onPlayRequested: (CastPayload) -> Unit,
    private val onControlRequested: (action: String, positionMs: Long) -> Unit = { _, _ -> }
) : WebSocketServer(InetSocketAddress(port)) {

    init {
        isReuseAddr = true
    }

    private val gson = Gson()

    companion object {
        private const val TAG = "TvWebSocketServer"
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d(TAG, "Client connected from ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.d(TAG, "Client disconnected: $reason")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        Log.d(TAG, "Received message on TV: $message")
        try {
            val payload = gson.fromJson(message, CastPayload::class.java)
            if (payload != null) {
                when (payload.action) {
                    "PLAY" -> {
                        onPlayRequested(payload)
                        conn.send("{\"status\":\"OK\",\"message\":\"Playing media\"}")
                    }
                    "PAUSE", "RESUME", "SEEK", "STOP" -> {
                        onControlRequested(payload.action, payload.startPositionMs)
                        conn.send("{\"status\":\"OK\",\"message\":\"Control executed\"}")
                    }
                    else -> {
                        conn.send("{\"status\":\"ERROR\",\"message\":\"Unknown action\"}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing cast payload", e)
            conn.send("{\"status\":\"ERROR\",\"message\":\"Invalid payload\"}")
        }
    }

    fun broadcastStatus(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        try {
            val payload = CastPayload(
                action = "STATUS_UPDATE",
                title = "",
                streamUrl = "",
                startPositionMs = positionMs,
                durationMs = durationMs
            )
            val json = gson.toJson(payload)
            broadcast(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting status", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket Server error", ex)
    }

    override fun onStart() {
        Log.d(TAG, "TvWebSocketServer started on port $port")
    }
}

package com.streamflixreborn.streamflix.cast

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

object MobileCastClient {
    private const val TAG = "MobileCastClient"
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun sendPayloadToTv(
        ipAddress: String,
        port: Int,
        payload: CastPayload,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val uri =
            try {
                URI("ws://$ipAddress:$port")
            } catch (e: Exception) {
                mainHandler.post { onError("Invalid URI: ws://$ipAddress:$port") }
                return
            }

        // Accessed from the WebSocket worker thread (onOpen/onError) and the main
        // thread (timeout), so it must be thread-safe to avoid firing both callbacks.
        val handled =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        // The runnables and the client reference each other, so the client is held
        // in a nullable var that is assigned right before connect().
        var client: WebSocketClient? = null

        // If the TV never answers (server dead, wrong port, wifi hiccup) the socket
        // would linger forever and accumulate across cast attempts. Force-close it
        // and surface the error so the caller can react instead of hanging.
        val timeoutRunnable =
            Runnable {
                if (handled.compareAndSet(false, true)) {
                    try {
                        client?.close()
                    } catch (ignored: Exception) {
                    }
                    mainHandler.post { onError("No response from TV ($ipAddress)") }
                }
            }

        // Fired on success: after a brief grace period the socket is closed so it
        // doesn't accumulate across several casts (the TV would otherwise keep one
        // stale connection per cast, which eventually makes new casts fail). The
        // grace period lets an immediate STATUS_UPDATE arrive before closing.
        val closeAfterSendRunnable =
            Runnable {
                try {
                    client?.close()
                } catch (ignored: Exception) {
                }
            }

        client =
            object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.d(TAG, "Connected to TV WebSocket server at $uri")
                    try {
                        val jsonPayload = gson.toJson(payload)
                        send(jsonPayload)
                        Log.d(TAG, "Sent payload: $jsonPayload")
                        if (handled.compareAndSet(false, true)) {
                            mainHandler.removeCallbacks(timeoutRunnable)
                            mainHandler.post { onSuccess() }
                            mainHandler.postDelayed(closeAfterSendRunnable, KEEP_ALIVE_MS)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send payload", e)
                        if (handled.compareAndSet(false, true)) {
                            mainHandler.removeCallbacks(timeoutRunnable)
                            mainHandler.post { onError("Failed to send payload: ${e.message}") }
                        }
                    }
                }

                override fun onMessage(message: String?) {
                    Log.d(TAG, "Received message from TV: $message")
                    if (message != null) {
                        try {
                            val statusPayload = gson.fromJson(message, CastPayload::class.java)
                            if (statusPayload?.action == "STATUS_UPDATE") {
                                mainHandler.post {
                                    CastControlManager.updatePosition(statusPayload.startPositionMs, statusPayload.durationMs)
                                }
                            }
                        } catch (ignored: Exception) {
                        }
                    }
                    close()
                }

                override fun onClose(
                    code: Int,
                    reason: String?,
                    remote: Boolean,
                ) {
                    Log.d(TAG, "WebSocket client closed: $reason")
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket client error", ex)
                    if (handled.compareAndSet(false, true)) {
                        val errorMsg = ex?.message ?: "Connection failed"
                        mainHandler.post { onError(errorMsg) }
                    }
                }
            }

        mainHandler.postDelayed(timeoutRunnable, CONNECT_TIMEOUT_MS)

        try {
            client?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting client", e)
            mainHandler.removeCallbacks(timeoutRunnable)
            if (handled.compareAndSet(false, true)) {
                mainHandler.post { onError("Error connecting: ${e.message}") }
            }
        }
    }

    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val KEEP_ALIVE_MS = 1_500L
}

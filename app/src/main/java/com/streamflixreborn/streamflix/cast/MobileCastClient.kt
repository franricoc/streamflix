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
        onError: (String) -> Unit
    ) {
        val uri = try {
            URI("ws://$ipAddress:$port")
        } catch (e: Exception) {
            mainHandler.post { onError("Invalid URI: ws://$ipAddress:$port") }
            return
        }

        var isHandled = false

        val client = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "Connected to TV WebSocket server at $uri")
                try {
                    val jsonPayload = gson.toJson(payload)
                    send(jsonPayload)
                    Log.d(TAG, "Sent payload: $jsonPayload")
                    if (!isHandled) {
                        isHandled = true
                        mainHandler.post { onSuccess() }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send payload", e)
                    if (!isHandled) {
                        isHandled = true
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
                    } catch (ignored: Exception) {}
                }
                close()
            }


            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "WebSocket client closed: $reason")
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WebSocket client error", ex)
                if (!isHandled) {
                    isHandled = true
                    val errorMsg = ex?.message ?: "Connection failed"
                    mainHandler.post { onError(errorMsg) }
                }
            }
        }

        try {
            client.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting client", e)
            if (!isHandled) {
                isHandled = true
                mainHandler.post { onError("Error connecting: ${e.message}") }
            }
        }
    }
}

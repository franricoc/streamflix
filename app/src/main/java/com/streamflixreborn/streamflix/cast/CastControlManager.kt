package com.streamflixreborn.streamflix.cast

import com.streamflixreborn.streamflix.cast.CastPayload
import com.streamflixreborn.streamflix.cast.MobileCastClient

object CastControlManager {

    data class ActiveCastSession(
        val device: CastPayload.DiscoveredDevice,
        val title: String,
        val subtitle: String?,
        var isPlaying: Boolean = true,
        var currentPositionMs: Long = 0L,
        var durationMs: Long = 0L
    )

    private var activeSession: ActiveCastSession? = null

    interface CastStateListener {
        fun onSessionStarted(session: ActiveCastSession)
        fun onSessionStateChanged(session: ActiveCastSession)
        fun onPositionUpdated(session: ActiveCastSession)
        fun onSessionEnded()
    }

    private val listeners = mutableListOf<CastStateListener>()

    fun registerListener(listener: CastStateListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        activeSession?.let { listener.onSessionStarted(it) }
    }

    fun unregisterListener(listener: CastStateListener) {
        listeners.remove(listener)
    }

    fun startSession(
        device: CastPayload.DiscoveredDevice,
        title: String,
        subtitle: String?,
        startPosMs: Long = 0L
    ) {
        val session = ActiveCastSession(
            device = device,
            title = title,
            subtitle = subtitle,
            isPlaying = true,
            currentPositionMs = startPosMs,
            durationMs = 0L
        )
        activeSession = session
        listeners.forEach { it.onSessionStarted(session) }
    }

    fun getActiveSession(): ActiveCastSession? = activeSession

    fun updatePosition(positionMs: Long, durationMs: Long) {
        val session = activeSession ?: return
        session.currentPositionMs = positionMs
        if (durationMs > 0) {
            session.durationMs = durationMs
        }
        listeners.forEach { it.onPositionUpdated(session) }
    }

    fun togglePlayPause() {
        val session = activeSession ?: return
        session.isPlaying = !session.isPlaying
        val action = if (session.isPlaying) "RESUME" else "PAUSE"
        sendControl(action, session.currentPositionMs)
        listeners.forEach { it.onSessionStateChanged(session) }
    }

    fun rewind10s() {
        val session = activeSession ?: return
        val newPos = maxOf(0L, session.currentPositionMs - 10_000L)
        session.currentPositionMs = newPos
        sendControl("SEEK", newPos)
        listeners.forEach { it.onPositionUpdated(session) }
    }

    fun fastForward10s() {
        val session = activeSession ?: return
        val maxDur = if (session.durationMs > 0) session.durationMs else Long.MAX_VALUE
        val newPos = minOf(maxDur, session.currentPositionMs + 10_000L)
        session.currentPositionMs = newPos
        sendControl("SEEK", newPos)
        listeners.forEach { it.onPositionUpdated(session) }
    }

    fun seekTo(targetMs: Long) {
        val session = activeSession ?: return
        session.currentPositionMs = targetMs
        sendControl("SEEK", targetMs)
        listeners.forEach { it.onPositionUpdated(session) }
    }

    fun stopSession() {
        sendControl("STOP", 0L)
        activeSession = null
        listeners.forEach { it.onSessionEnded() }
    }

    private fun sendControl(action: String, positionMs: Long) {
        val session = activeSession ?: return
        val payload = CastPayload(
            action = action,
            title = "",
            streamUrl = "",
            startPositionMs = positionMs
        )
        MobileCastClient.sendPayloadToTv(
            ipAddress = session.device.ipAddress,
            port = session.device.port,
            payload = payload,
            onSuccess = {},
            onError = {}
        )
    }
}

package com.streamflixreborn.streamflix.cast.ui

import android.content.Context
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.streamflixreborn.streamflix.cast.CastPayload
import com.streamflixreborn.streamflix.cast.MobileCastClient

object CastRemoteControlDialog {

    private var isPlaying = true

    fun show(
        context: Context,
        device: CastPayload.DiscoveredDevice,
        title: String,
        subtitle: String?
    ) {
        isPlaying = true

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        val tvInfo = TextView(context).apply {
            text = "Conectado a ${device.name} (${device.ipAddress})"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        val tvMediaTitle = TextView(context).apply {
            text = title
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }

        val tvMediaSub = TextView(context).apply {
            text = subtitle ?: ""
            textSize = 14f
            setTextColor(android.graphics.Color.LTGRAY)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 4, 0, 24)
        }

        val controlsRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }

        val btnRew = Button(context).apply {
            text = "⏪ -10s"
            setOnClickListener {
                sendControlCommand(device, "SEEK", 0L)
            }
        }

        val btnPlayPause = Button(context).apply {
            text = "⏸️ Pausa"
            setOnClickListener {
                isPlaying = !isPlaying
                val action = if (isPlaying) "RESUME" else "PAUSE"
                text = if (isPlaying) "⏸️ Pausa" else "▶️ Reproducir"
                sendControlCommand(device, action, 0L)
            }
        }

        val btnFwd = Button(context).apply {
            text = "⏩ +10s"
            setOnClickListener {
                sendControlCommand(device, "SEEK", 0L)
            }
        }

        controlsRow.addView(btnRew)
        controlsRow.addView(btnPlayPause)
        controlsRow.addView(btnFwd)

        val btnStop = Button(context).apply {
            text = "⏹️ Detener Reproducción en TV"
            setTextColor(android.graphics.Color.RED)
            setOnClickListener {
                sendControlCommand(device, "STOP", 0L)
            }
        }

        layout.addView(tvInfo)
        layout.addView(tvMediaTitle)
        if (!subtitle.isNullOrEmpty()) layout.addView(tvMediaSub)
        layout.addView(controlsRow)
        layout.addView(btnStop)

        val dialog = AlertDialog.Builder(context)
            .setTitle("📺 Control Remoto TV")
            .setView(layout)
            .setPositiveButton("Cerrar Control", null)
            .create()

        btnStop.setOnClickListener {
            sendControlCommand(device, "STOP", 0L)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun sendControlCommand(
        device: CastPayload.DiscoveredDevice,
        action: String,
        positionMs: Long
    ) {
        val payload = CastPayload(
            action = action,
            title = "",
            streamUrl = "",
            startPositionMs = positionMs
        )

        MobileCastClient.sendPayloadToTv(
            ipAddress = device.ipAddress,
            port = device.port,
            payload = payload,
            onSuccess = {},
            onError = {}
        )
    }
}

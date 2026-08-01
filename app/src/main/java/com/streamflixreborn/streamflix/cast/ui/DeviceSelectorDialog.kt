package com.streamflixreborn.streamflix.cast.ui

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.streamflixreborn.streamflix.cast.CastPayload
import com.streamflixreborn.streamflix.cast.DeviceDiscoveryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object DeviceSelectorDialog {

    fun show(
        context: Context,
        onDeviceSelected: (CastPayload.DiscoveredDevice) -> Unit
    ) {
        val discoveryManager = DeviceDiscoveryManager(context)
        discoveryManager.startDiscovery()

        val dialogScope = CoroutineScope(Dispatchers.Main + Job())

        val builder = AlertDialog.Builder(context)
        builder.setTitle("📺 Seleccionar Android TV")
        builder.setMessage("Buscando dispositivos en la misma red Wi-Fi...")

        var dialog: AlertDialog? = null

        val job = dialogScope.launch {
            discoveryManager.discoveredDevices.collect { devices ->
                if (dialog?.isShowing == true) {
                    val deviceNames = devices.map { "${it.name} (${it.ipAddress})" }.toTypedArray()

                    val newBuilder = AlertDialog.Builder(context)
                        .setTitle("📺 Seleccionar Android TV")

                    if (devices.isEmpty()) {
                        newBuilder.setMessage("No se encontraron TVs automáticamente.\nAsegúrate de tener la app abierta en la TV.")
                    } else {
                        newBuilder.setItems(deviceNames) { d, which ->
                            discoveryManager.stopDiscovery()
                            d.dismiss()
                            onDeviceSelected(devices[which])
                        }
                    }

                    newBuilder.setPositiveButton("Ingresar IP manualmente") { d, _ ->
                        discoveryManager.stopDiscovery()
                        d.dismiss()
                        showManualIpDialog(context, onDeviceSelected)
                    }

                    newBuilder.setNegativeButton("Cancelar") { d, _ ->
                        discoveryManager.stopDiscovery()
                        d.dismiss()
                    }

                    val newDialog = newBuilder.create()
                    dialog?.dismiss()
                    dialog = newDialog
                    newDialog.show()
                }
            }
        }

        builder.setPositiveButton("Ingresar IP manualmente") { d, _ ->
            job.cancel()
            discoveryManager.stopDiscovery()
            d.dismiss()
            showManualIpDialog(context, onDeviceSelected)
        }

        builder.setNegativeButton("Cancelar") { d, _ ->
            job.cancel()
            discoveryManager.stopDiscovery()
            d.dismiss()
        }

        dialog = builder.create()
        dialog?.show()
    }

    private fun showManualIpDialog(
        context: Context,
        onDeviceSelected: (CastPayload.DiscoveredDevice) -> Unit
    ) {
        val input = EditText(context).apply {
            hint = "Ej: 192.168.1.50"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        AlertDialog.Builder(context)
            .setTitle("Ingresar IP de la TV")
            .setView(input)
            .setPositiveButton("Conectar") { dialog, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    onDeviceSelected(
                        CastPayload.DiscoveredDevice(
                            name = "Android TV ($ip)",
                            ipAddress = ip,
                            port = 8080
                        )
                    )
                } else {
                    Toast.makeText(context, "IP no válida", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

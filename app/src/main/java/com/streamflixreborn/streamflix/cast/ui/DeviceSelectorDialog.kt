package com.streamflixreborn.streamflix.cast.ui

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.streamflixreborn.streamflix.cast.CastPayload
import com.streamflixreborn.streamflix.cast.DeviceDiscoveryManager
import com.streamflixreborn.streamflix.utils.DialogTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object DeviceSelectorDialog {

    private const val PREFS_NAME = "streamflix_cast_prefs"
    private const val KEY_LAST_TV_IP = "last_tv_ip"
    private const val KEY_LAST_TV_NAME = "last_tv_name"

    fun show(
        context: Context,
        onDeviceSelected: (CastPayload.DiscoveredDevice) -> Unit
    ) {
        val discoveryManager = DeviceDiscoveryManager(context)
        discoveryManager.startDiscovery()

        val dialogScope = CoroutineScope(Dispatchers.Main + Job())
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastIp = prefs.getString(KEY_LAST_TV_IP, null)
        val lastName = prefs.getString(KEY_LAST_TV_NAME, null) ?: "Android TV"

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val statusLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 24)
        }

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            visibility = View.VISIBLE
        }
        statusLayout.addView(progressBar)

        val statusText = TextView(context).apply {
            text = "  Buscando TVs en la misma red Wi-Fi..."
            textSize = 14f
            setTextColor(Color.LTGRAY)
        }
        statusLayout.addView(statusText)
        rootLayout.addView(statusLayout)

        val deviceList = mutableListOf<CastPayload.DiscoveredDevice>()
        val displayItems = mutableListOf<String>()

        val adapter = object : ArrayAdapter<String>(
            context,
            android.R.layout.simple_list_item_1,
            displayItems
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(Color.WHITE)
                view.textSize = 15f
                view.setPadding(16, 24, 16, 24)
                return view
            }
        }

        val listView = ListView(context).apply {
            this.adapter = adapter
            divider = null
            dividerHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rootLayout.addView(listView)

        var dialog: AlertDialog? = null

        fun selectAndFinish(device: CastPayload.DiscoveredDevice) {
            prefs.edit()
                .putString(KEY_LAST_TV_IP, device.ipAddress)
                .putString(KEY_LAST_TV_NAME, device.name.replace(" (Última usada)", ""))
                .apply()
            dialogScope.launch { discoveryManager.stopDiscovery() }
            dialog?.dismiss()
            onDeviceSelected(device)
        }

        if (!lastIp.isNullOrBlank()) {
            val rememberedDevice = CastPayload.DiscoveredDevice(
                name = "$lastName (Última usada)",
                ipAddress = lastIp,
                port = 8080
            )
            deviceList.add(rememberedDevice)
            displayItems.add("⭐ ${rememberedDevice.name}\n    ${rememberedDevice.ipAddress}")
            adapter.notifyDataSetChanged()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in deviceList.indices) {
                selectAndFinish(deviceList[position])
            }
        }

        val builder = AlertDialog.Builder(context)
            .setTitle("📺 Seleccionar Android TV")
            .setView(rootLayout)
            .setPositiveButton("Ingresar IP") { d, _ ->
                dialogScope.launch { discoveryManager.stopDiscovery() }
                d.dismiss()
                showManualIpDialog(context, onDeviceSelected)
            }
            .setNegativeButton("Cancelar") { d, _ ->
                dialogScope.launch { discoveryManager.stopDiscovery() }
                d.dismiss()
            }
            .setOnDismissListener {
                dialogScope.launch { discoveryManager.stopDiscovery() }
            }

        dialog = builder.create().also { DialogTheme.style(it) }
        dialog.show()

        dialogScope.launch {
            discoveryManager.discoveredDevices.collect { discovered ->
                if (dialog?.isShowing == true) {
                    deviceList.clear()
                    displayItems.clear()

                    for (dev in discovered) {
                        deviceList.add(dev)
                        displayItems.add("📺 ${dev.name}\n    ${dev.ipAddress}")
                    }

                    if (!lastIp.isNullOrBlank() && discovered.none { it.ipAddress == lastIp }) {
                        val remembered = CastPayload.DiscoveredDevice(
                            name = "$lastName (Última usada)",
                            ipAddress = lastIp,
                            port = 8080
                        )
                        deviceList.add(remembered)
                        displayItems.add("⭐ ${remembered.name}\n    ${remembered.ipAddress}")
                    }

                    adapter.notifyDataSetChanged()

                    if (discovered.isNotEmpty()) {
                        statusText.text = "  Dispositivos encontrados (${discovered.size}):"
                        progressBar.visibility = View.GONE
                    }
                }
            }
        }

        dialogScope.launch {
            delay(6000)
            if (dialog?.isShowing == true && discoveryManager.discoveredDevices.value.isEmpty()) {
                statusText.text = "  Asegúrate de tener StreamFlix abierto en la TV."
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun showManualIpDialog(
        context: Context,
        onDeviceSelected: (CastPayload.DiscoveredDevice) -> Unit
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastIp = prefs.getString(KEY_LAST_TV_IP, "")

        val input = EditText(context).apply {
            hint = "Ej: 192.168.1.50"
            inputType = InputType.TYPE_CLASS_TEXT
            if (!lastIp.isNullOrBlank()) {
                setText(lastIp)
                selectAll()
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Ingresar IP de la TV")
            .setView(input)
            .setPositiveButton("Conectar") { dialog, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    prefs.edit()
                        .putString(KEY_LAST_TV_IP, ip)
                        .putString(KEY_LAST_TV_NAME, "Android TV")
                        .apply()
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
            .create()
        DialogTheme.style(dialog)
        dialog.show()
    }
}

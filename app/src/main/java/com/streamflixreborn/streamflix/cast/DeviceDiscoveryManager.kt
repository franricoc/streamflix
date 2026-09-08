package com.streamflixreborn.streamflix.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

class DeviceDiscoveryManager(private val context: Context) {

    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _discoveredDevices = MutableStateFlow<List<CastPayload.DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<CastPayload.DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    private val resolveQueue = java.util.ArrayDeque<NsdServiceInfo>()
    private var isResolving = false

    companion object {
        const val SERVICE_TYPE = "_streamflix-cast._tcp."
        const val SERVICE_NAME = "StreamFlix-TV"
        private const val TAG = "DeviceDiscoveryManager"
    }

    // --- TV SIDE: Register Service ---
    fun registerTvService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$SERVICE_NAME-${Build.MODEL}"
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD Service registered: ${NsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(arg0: NsdServiceInfo, arg1: Int) {
                Log.e(TAG, "NSD Service registration failed: $arg1")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d(TAG, "NSD Service unregistered: ${arg0.serviceName}")
            }

            override fun onUnregistrationFailed(arg0: NsdServiceInfo, arg1: Int) {
                Log.e(TAG, "NSD Service unregistration failed: $arg1")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering NSD service", e)
        }
    }

    fun unregisterTvService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering NSD service", e)
            }
            registrationListener = null
        }
    }

    // --- MOBILE SIDE: Discover Services ---
    fun startDiscovery() {
        stopDiscovery()
        _discoveredDevices.value = emptyList()

        try {
            if (multicastLock == null) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                multicastLock = wm?.createMulticastLock("streamflix:cast_discovery")?.apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.acquire()
            Log.d(TAG, "MulticastLock acquired for discovery")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock", e)
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName}")
                if (service.serviceType.contains("_streamflix-cast")) {
                    queueResolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                _discoveredDevices.value = _discoveredDevices.value.filter {
                    it.name != service.serviceName
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code:$errorCode")
                try {
                    nsdManager.stopServiceDiscovery(this)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping discovery on failure", e)
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop Discovery failed: Error code:$errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service discovery", e)
        }
    }

    private fun queueResolveService(service: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.add(service)
            if (!isResolving) {
                processNextResolve()
            }
        }
    }

    private fun processNextResolve() {
        synchronized(resolveQueue) {
            val next = resolveQueue.poll()
            if (next == null) {
                isResolving = false
                return
            }
            isResolving = true
            resolveServiceInternal(next)
        }
    }

    private fun resolveServiceInternal(service: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode for ${serviceInfo.serviceName}")
                processNextResolve()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service resolved: ${serviceInfo.serviceName} -> ${serviceInfo.host}:${serviceInfo.port}")
                val host: InetAddress? = serviceInfo.host
                if (host != null) {
                    val ip = host.hostAddress
                    if (ip != null) {
                        val device = CastPayload.DiscoveredDevice(
                            name = serviceInfo.serviceName.replace("$SERVICE_NAME-", ""),
                            ipAddress = ip,
                            port = serviceInfo.port
                        )

                        val currentList = _discoveredDevices.value.toMutableList()
                        if (currentList.none { it.ipAddress == ip && it.port == serviceInfo.port }) {
                            currentList.add(device)
                            _discoveredDevices.value = currentList
                        }
                    }
                }
                processNextResolve()
            }
        }

        try {
            nsdManager.resolveService(service, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving service", e)
            processNextResolve()
        }
    }

    fun stopDiscovery() {
        synchronized(resolveQueue) {
            resolveQueue.clear()
            isResolving = false
        }
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
            discoveryListener = null
        }
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "MulticastLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing multicast lock", e)
        }
    }
}

/*
 * Foreground host for the lab Shizuku audio bridge.
 *
 * Keeps the normal app process, CPU and Wi-Fi awake while the privileged
 * UserService performs capture and packetization in the shell process.
 */

package com.cuscus.wifiaudiostreaming.shizuku

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.cuscus.wifiaudiostreaming.NetworkManager
import com.cuscus.wifiaudiostreaming.NotificationCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ShizukuBridgeHostService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var statusJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationCenter.ensureChannels(this)
        startForeground(
            NotificationCenter.ID_SERVER,
            NotificationCenter.shizukuBridgeNotification(
                this,
                NetworkManager.connectionStatus.value.ifBlank { getString(com.cuscus.wifiaudiostreaming.R.string.shizuku_bridge_active) }
            )
        )

        acquireLocks()

        if (statusJob?.isActive != true) {
            statusJob = scope.launch {
                NetworkManager.connectionStatus.collect { status ->
                    NotificationCenter.post(
                        this@ShizukuBridgeHostService,
                        NotificationCenter.ID_SERVER,
                        NotificationCenter.shizukuBridgeNotification(
                            this@ShizukuBridgeHostService,
                            status.ifBlank { getString(com.cuscus.wifiaudiostreaming.R.string.shizuku_bridge_active) }
                        )
                    )
                    delay(350)
                }
            }
        }

        // If Android recreated the normal process but Shizuku's daemon
        // UserService survived, reattach to it instead of starting a new capture.
        ShizukuAudioBridgeManager.rebindExisting(this)

        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = runCatching {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "wfas:shizuku-bridge"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }

        if (wifiLock?.isHeld != true) {
            wifiLock = runCatching {
                val wifiManager =
                    applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiManager.createWifiLock(mode, "wfas:shizuku-bridge-wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
        statusJob = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationCenter.cancel(this, NotificationCenter.ID_SERVER)
        super.onDestroy()
    }
}

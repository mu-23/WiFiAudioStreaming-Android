package com.cuscus.wifiaudiostreaming

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.cuscus.wifiaudiostreaming.shizuku.ShizukuAudioBridgeManager
import kotlin.math.roundToInt

/**
 * Compact volume popup launched from the foreground notification.
 *
 * Android notification RemoteViews do not support SeekBar, so the notification
 * itself stays a system-safe progress card. Tapping it opens this tiny dialog,
 * where the slider can be tapped or dragged directly.
 */
class VolumeControlActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_CLIENT
        val isServer = mode == MODE_SERVER
        val maxPercent = if (isServer) 200 else 100
        val current = if (isServer) {
            NetworkManager.serverVolume.value
        } else {
            NetworkManager.clientVolume.value
        }

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).roundToInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = getString(
                if (isServer) R.string.volume_popup_sender_title
                else R.string.volume_popup_receiver_title
            )
            textSize = 20f
            setPadding(0, 0, 0, dp(8))
        }

        val valueLabel = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
        }

        val seekBar = SeekBar(this).apply {
            max = maxPercent
            progress = (current * 100f).roundToInt().coerceIn(0, maxPercent)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val hint = TextView(this).apply {
            text = getString(R.string.volume_popup_hint)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        fun updateLabel(progress: Int) {
            valueLabel.text = getString(R.string.notif_volume, progress)
        }

        updateLabel(seekBar.progress)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabel(progress)
                if (!fromUser) return
                val value = progress / 100f
                if (isServer) {
                    NetworkManager.serverVolume.value = value.coerceIn(0f, 2f)
                    ShizukuAudioBridgeManager.setVolume(value)
                } else {
                    NetworkManager.setClientVolume(value)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        root.addView(title)
        root.addView(valueLabel)
        root.addView(seekBar)
        root.addView(hint)
        setContentView(root)

        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).roundToInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    companion object {
        const val EXTRA_MODE = "volume_mode"
        const val MODE_SERVER = "server"
        const val MODE_CLIENT = "client"
    }
}

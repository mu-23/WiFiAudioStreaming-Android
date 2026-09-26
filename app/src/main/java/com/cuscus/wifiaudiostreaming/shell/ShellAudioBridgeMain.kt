/*
 * Experimental shell audio bridge for the WFAS audio-bridge lab branch.
 *
 * Copyright (c) 2026 Marco Morosi and contributors
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL.
 *
 * This file is intentionally isolated to the audio-bridge-lab branch.
 */

package com.cuscus.wifiaudiostreaming.shell

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Minimal WFAS sender that is meant to be started from adb shell/app_process.
 *
 * The process therefore runs with the Android shell identity instead of the
 * regular application UID. The first experiment uses REMOTE_SUBMIX so we can
 * verify whether the device exposes the mixed system output to shell without
 * MediaProjection.
 *
 * This is deliberately small: no discovery broadcast, no auth/encryption and
 * no UI integration yet. The existing Android client can connect by IP.
 */
object ShellAudioBridgeMain {
    private const val PROTOCOL_VERSION = 2
    private const val DEFAULT_PORT = 9090
    private const val DEFAULT_SAMPLE_RATE = 48_000
    private const val DEFAULT_CHANNELS = 2
    private const val DEFAULT_PACKET_BYTES = 512
    private const val MAGIC_0: Byte = 0x57
    private const val MAGIC_1: Byte = 0x46
    private const val HEADER_SIZE = 10
    private const val SHELL_UID = 2000

    data class Config(
        val port: Int = DEFAULT_PORT,
        val sampleRate: Int = DEFAULT_SAMPLE_RATE,
        val channels: Int = DEFAULT_CHANNELS,
        val packetBytes: Int = DEFAULT_PACKET_BYTES
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val config = parseArgs(args) ?: return
        println(
            "[WFAS-SHELL] starting uid=${Process.myUid()} port=${config.port} " +
                "sr=${config.sampleRate} ch=${config.channels} packet=${config.packetBytes}"
        )
        if (Process.myUid() != SHELL_UID) {
            System.err.println(
                "[WFAS-SHELL] WARNING: uid is not shell (2000). " +
                    "REMOTE_SUBMIX will normally be denied to a regular app UID."
            )
        }

        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }

        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.receiveBufferSize = 1 shl 20
            socket.sendBufferSize = 1 shl 20
            socket.soTimeout = 1000
            socket.bind(InetSocketAddress(config.port))
            println("[WFAS-SHELL] listening on UDP :${config.port}")

            while (true) {
                val firstClient = waitForClient(socket)
                println("[WFAS-SHELL] client connected: $firstClient")
                runSession(socket, firstClient, config)
                println("[WFAS-SHELL] session ended; waiting for another client")
            }
        }
    }

    private fun waitForClient(socket: DatagramSocket): InetSocketAddress {
        val buf = ByteArray(2048)
        while (true) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val remote = InetSocketAddress(packet.address, packet.port)
            val text = packet.data.decodeToString(0, packet.length).trim()
            when {
                text == "MODE_PROBE" -> sendText(socket, remote, "UNICAST")
                text.startsWith("HELLO_FROM_CLIENT") -> {
                    val version = token(text, "v")?.toIntOrNull() ?: 0
                    if (version != PROTOCOL_VERSION) {
                        sendText(socket, remote, "WFAS_INCOMPATIBLE;v=$PROTOCOL_VERSION")
                        continue
                    }
                    sendText(socket, remote, "HELLO_ACK;v=$PROTOCOL_VERSION")
                    return remote
                }
            }
        }
    }

    private fun runSession(
        socket: DatagramSocket,
        initialClient: InetSocketAddress,
        config: Config
    ) {
        val channelMask = if (config.channels == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val frameSize = config.channels * 2
        var packetBytes = config.packetBytes.coerceIn(128, 1390 - HEADER_SIZE)
        packetBytes -= packetBytes % frameSize
        if (packetBytes < frameSize) packetBytes = frameSize

        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) {
            "AudioRecord unsupported: sr=${config.sampleRate} channels=${config.channels}, code=$minBuffer"
        }

        val recordBuffer = max(minBuffer, packetBytes * 4)
        val recorder = try {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.REMOTE_SUBMIX,
                config.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBuffer
            )
        } catch (t: Throwable) {
            System.err.println(
                "[WFAS-SHELL] cannot create REMOTE_SUBMIX AudioRecord: " +
                    "${t.javaClass.simpleName}: ${t.message}"
            )
            throw t
        }

        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "REMOTE_SUBMIX AudioRecord is not initialized"
        }

        val alive = AtomicBoolean(true)
        val client = AtomicReference(initialClient)
        val clientIp: InetAddress = initialClient.address

        val controlThread = thread(name = "wfas-shell-control", isDaemon = true) {
            val buf = ByteArray(2048)
            while (alive.get()) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (t: Throwable) {
                    if (alive.get()) {
                        System.err.println("[WFAS-SHELL] control receive failed: ${t.message}")
                    }
                    break
                }

                val remote = InetSocketAddress(packet.address, packet.port)
                val text = packet.data.decodeToString(0, packet.length).trim()
                when {
                    text == "MODE_PROBE" -> sendText(socket, remote, "UNICAST")
                    text.startsWith("HELLO_FROM_CLIENT") -> {
                        val version = token(text, "v")?.toIntOrNull() ?: 0
                        if (version != PROTOCOL_VERSION) {
                            sendText(socket, remote, "WFAS_INCOMPATIBLE;v=$PROTOCOL_VERSION")
                        } else if (remote.address == clientIp) {
                            // The Android client may reconnect with a new ephemeral
                            // source port. Follow it without restarting capture.
                            client.set(remote)
                            sendText(socket, remote, "HELLO_ACK;v=$PROTOCOL_VERSION")
                            println("[WFAS-SHELL] client endpoint refreshed: $remote")
                        } else {
                            sendText(socket, remote, "WFAS_BUSY")
                        }
                    }
                    text == "CLIENT_BYE" && remote.address == clientIp -> {
                        alive.set(false)
                    }
                }
            }
        }

        val pingThread = thread(name = "wfas-shell-ping", isDaemon = true) {
            var failures = 0
            while (alive.get()) {
                try {
                    Thread.sleep(1000)
                    sendText(socket, client.get(), "PING")
                    failures = 0
                } catch (t: Throwable) {
                    failures++
                    if (failures >= 3) {
                        System.err.println("[WFAS-SHELL] ping failed 3 times: ${t.message}")
                        alive.set(false)
                    }
                }
            }
        }

        val pcm = ByteArray(packetBytes)
        var seq = 0
        var samplePosition = 0L
        var packets = 0L

        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "REMOTE_SUBMIX failed to enter RECORDSTATE_RECORDING"
            }
            println(
                "[WFAS-SHELL] REMOTE_SUBMIX recording started, audioPacket=${packetBytes}B " +
                    "(~${"%.2f".format(packetBytes * 1000.0 / (config.sampleRate * frameSize))}ms)"
            )

            while (alive.get()) {
                val read = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    throw IllegalStateException("AudioRecord.read failed: $read")
                }
                val aligned = read - (read % frameSize)
                if (aligned <= 0) continue

                val out = ByteArray(HEADER_SIZE + aligned)
                out[0] = MAGIC_0
                out[1] = MAGIC_1
                out[2] = PROTOCOL_VERSION.toByte()
                out[3] = 0
                out[4] = ((seq ushr 8) and 0xFF).toByte()
                out[5] = (seq and 0xFF).toByte()
                ByteBuffer.wrap(out, 6, 4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt((samplePosition and 0xFFFFFFFFL).toInt())
                System.arraycopy(pcm, 0, out, HEADER_SIZE, aligned)

                val target = client.get()
                socket.send(DatagramPacket(out, out.size, target.address, target.port))

                seq = (seq + 1) and 0xFFFF
                samplePosition += aligned / frameSize
                packets++
                if (packets == 1L || packets % 2000L == 0L) {
                    println("[WFAS-SHELL] sent packets=$packets seq=$seq target=$target")
                }
            }
        } finally {
            alive.set(false)
            runCatching { recorder.stop() }
            recorder.release()
            runCatching { controlThread.join(1000) }
            runCatching { pingThread.join(1000) }
        }
    }

    private fun sendText(socket: DatagramSocket, remote: InetSocketAddress, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, remote.address, remote.port))
    }

    private fun token(message: String, name: String): String? =
        message.split(';')
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')

    private fun parseArgs(args: Array<String>): Config? {
        if (args.any { it == "--help" || it == "-h" }) {
            printUsage()
            return null
        }

        var port = DEFAULT_PORT
        var sampleRate = DEFAULT_SAMPLE_RATE
        var channels = DEFAULT_CHANNELS
        var packetBytes = DEFAULT_PACKET_BYTES

        var i = 0
        fun needValue(flag: String): String {
            if (i + 1 >= args.size) error("Missing value for $flag")
            i += 1
            return args[i]
        }

        while (i < args.size) {
            when (val arg = args[i]) {
                "--port" -> port = needValue(arg).toInt()
                "--sample-rate" -> sampleRate = needValue(arg).toInt()
                "--channels" -> channels = needValue(arg).toInt()
                "--packet-bytes" -> packetBytes = needValue(arg).toInt()
                else -> error("Unknown argument: $arg")
            }
            i += 1
        }

        require(port in 1024..65535) { "port must be 1024..65535" }
        require(sampleRate in 8_000..192_000) { "sample-rate out of range" }
        require(channels == 1 || channels == 2) { "channels must be 1 or 2" }

        return Config(port, sampleRate, channels, packetBytes)
    }

    private fun printUsage() {
        println(
            """
            WFAS shell audio bridge (experimental)

            Usage:
              ShellAudioBridgeMain [--port 9090] [--sample-rate 48000]
                                   [--channels 2] [--packet-bytes 512]

            Start it via app_process from an adb shell so the process runs as
            Android's shell UID (2000), not as the regular application UID.
            """.trimIndent()
        )
    }
}

/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 * --------------------------------------------------------------------------
 * SnapcastStreamClient.kt
 *
 * Client audio Snapcast: TCP sulla porta 1704.
 *
 * Il punto di Snapcast non e' ricevere audio, e' riprodurlo SINCRONIZZATO con
 * gli altri client. Ogni blocco audio porta il momento in cui e' stato
 * catturato, espresso nell'orologio del SERVER, e va suonato a
 * `timestamp + bufferMs`. Poiche' i due orologi non coincidono, si stima
 * continuamente lo scarto scambiando messaggi TIME.
 *
 * La stima usa il minimo delle ultime misure invece della media: lo scarto
 * vero e' nascosto dal ritardo di rete, che e' sempre additivo e molto
 * variabile. Il campione con andata e ritorno piu' breve e' quello meno
 * inquinato, e mediare non lo migliora, lo peggiora.
 *
 * Codec: PCM riprodotto direttamente, FLAC decodificato da FFmpeg. Il
 * riferimento e' il codec header che il server manda all'inizio.
 */

package com.cuscus.wifiaudiostreaming.snapcast

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import com.cuscus.wifiaudiostreaming.PcmPlaybackSink

/*
 * Portato dall'app desktop: stesso file, stesso protocollo.
 *
 * Rispetto alla versione desktop cambiano tre cose e basta: il package, il
 * registro di debug, e il decodificatore FLAC — la' e' FFmpeg, qui e'
 * MediaCodec, perche' su Android il decodificatore FLAC ce l'ha gia' il
 * sistema e portarsi dietro una libreria nativa per rifare quel che il
 * telefono sa fare sarebbe peso inutile. Opus resta fuori da entrambi i lati:
 * snapserver lo usa solo se glielo si chiede.
 *
 * Tutto il resto — sincronizzazione degli orologi, volume comandato dal
 * server, riapertura della linea quando cambia il buffer — e' identico riga
 * per riga, e va tenuto tale.
 */


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

enum class SnapStreamState { IDLE, CONNECTING, BUFFERING, PLAYING, ERROR }

data class SnapStreamStatus(
    val state: SnapStreamState = SnapStreamState.IDLE,
    val server: SnapcastServerRef? = null,
    val codec: String = "",
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bufferMs: Int = 0,
    /** Scarto stimato fra il nostro orologio e quello del server. */
    val clockOffsetMs: Double = 0.0,
    /** Quanto siamo lontani dal momento giusto di riproduzione. */
    val syncErrorMs: Double = 0.0,
    val playoutBufferMs: Int = 0,
    val volumePercent: Int = 100,
    val muted: Boolean = false,
    val errorKey: String? = null,
    val errorDetail: String? = null
) {
    val active: Boolean get() = state == SnapStreamState.BUFFERING || state == SnapStreamState.PLAYING ||
                                state == SnapStreamState.CONNECTING
}

/**
 * Stima dello scarto fra orologio locale e orologio del server.
 *
 * Tenuta separata perche' e' il cuore della sincronizzazione ed e' logica pura:
 * si prova senza rete.
 */
class SnapcastClockSync(private val window: Int = 20) {

    private val samples = ArrayDeque<Double>()

    /** Scarto in microsecondi: tempoServer ≈ tempoLocale + offset. */
    @Volatile var offsetMicros: Double = 0.0
        private set

    @Volatile var samplesSeen: Int = 0
        private set

    /**
     * @param c1 istante locale in cui abbiamo spedito il TIME
     * @param serverLatency payload della risposta: (ricezione server) − c1
     * @param s2 istante server in cui il server ha risposto
     * @param c2 istante locale in cui abbiamo ricevuto la risposta
     */
    fun update(c1: Long, serverLatency: Long, s2: Long, c2: Long) {
        val s2c = c2 - s2                   // locale − server, sul ritorno
        val offset = (serverLatency - s2c) / 2.0
        val rtt = (c2 - c1).toDouble()

        samples.addLast(offset)
        rtts.addLast(rtt)
        while (samples.size > window) { samples.removeFirst(); rtts.removeFirst() }
        samplesSeen++

        // Si tiene l'offset del campione con l'andata e ritorno piu' corto:
        // e' quello in cui il ritardo di rete ha inquinato meno la misura.
        var best = 0
        for (i in rtts.indices) if (rtts[i] < rtts[best]) best = i
        offsetMicros = samples[best]
    }

    private val rtts = ArrayDeque<Double>()

    fun reset() {
        samples.clear(); rtts.clear(); samplesSeen = 0; offsetMicros = 0.0
    }

    /** Converte un istante dell'orologio server in orologio locale. */
    fun serverToLocal(serverMicros: Long): Long = serverMicros - offsetMicros.toLong()
}

class SnapcastStreamClient(
    private val server: SnapcastServerRef,
    private val clientId: String,
    private val clientName: String,
    private val onStatus: (SnapStreamStatus) -> Unit,
    private val onPcm: ((ShortArray) -> Unit)?,
    /** Apre l'uscita audio. bufferMs e' quello imposto dal server. */
    private val openPlayer: (sampleRate: Int, channels: Int, bufferMs: Int) -> PcmPlaybackSink?
) {
    private var job: Job? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var stopping = false

    private val clock = SnapcastSteadyClock()
    private val sync = SnapcastClockSync()

    @Volatile private var status = SnapStreamStatus(server = server)
    @Volatile private var sink: PcmPlaybackSink? = null
    @Volatile private var decoder: SnapcastAudioDecoder? = null

    /**
     * Volume di QUESTO client, comandato dal server.
     *
     * In Snapcast il volume del singolo client non e' una manopola locale: e'
     * il server a deciderlo — dal mixer, da un'app di controllo, da chiunque —
     * e il client deve applicarlo al proprio audio. Riceverlo e limitarsi a
     * mostrarlo, come faceva questo codice, significa che il cursore si muove e
     * il suono resta identico.
     */
    @Volatile private var volumePercent = 100
    @Volatile private var muted = false

    fun start(scope: CoroutineScope) {
        if (job != null) return
        stopping = false
        job = scope.launch(Dispatchers.IO) { run(this) }
    }

    fun stop() {
        stopping = true
        runCatching { socket?.close() }
        runCatching { decoder?.close() }
        runCatching { sink?.close() }
        job?.cancel()
        job = null
        publish(status.copy(state = SnapStreamState.IDLE))
    }

    private fun publish(s: SnapStreamStatus) { status = s; onStatus(s) }

    private fun run(scope: CoroutineScope) {
        publish(SnapStreamStatus(state = SnapStreamState.CONNECTING, server = server))
        var timeJob: Job? = null
        try {
            val sock = Socket().apply {
                connect(InetSocketAddress(server.host, server.streamPort), 5000)
                tcpNoDelay = true
            }
            socket = sock
            val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(sock.getOutputStream(), 8 * 1024)

            sendHello(output)

            // I TIME vanno mandati spesso all'inizio (serve una stima subito) e
            // poi di rado: l'orologio deriva lentamente.
            timeJob = scope.launch(Dispatchers.IO) {
                var n = 0
                while (isActive && !stopping) {
                    runCatching { sendTime(output) }
                    n++
                    kotlinx.coroutines.delay(if (n < 10) 300L else 3000L)
                }
            }

            while (scope.isActive && !stopping) {
                val frame = SnapcastWire.readFrame(input)
                val received = clock.nowMicros()
                when (frame.header.type) {
                    SnapcastMessageType.SERVER_SETTINGS -> handleServerSettings(frame)
                    SnapcastMessageType.CODEC_HEADER   -> handleCodecHeader(frame)
                    SnapcastMessageType.WIRE_CHUNK     -> handleWireChunk(frame)
                    SnapcastMessageType.TIME           -> handleTime(frame, received)
                    SnapcastMessageType.STREAM_TAGS    -> Unit
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            if (!stopping) {
                SnapLog.d("[Snapcast/stream] ${e.message}")
                publish(status.copy(state = SnapStreamState.ERROR,
                                    errorKey = "snap_err_stream", errorDetail = e.message))
            }
        } finally {
            timeJob?.cancel()
            runCatching { socket?.close() }
            runCatching { decoder?.close() }; decoder = null
            runCatching { sink?.close() };    sink = null
            if (!stopping) publish(status.copy(state = SnapStreamState.IDLE))
        }
    }

    // ── Handshake ──────────────────────────────────────────────────────────

    private fun sendHello(out: java.io.OutputStream) {
        val payload = SnapcastWire.stringPayload(SnapJsonWriter.write {
            put("MAC", clientId)
            put("HostName", clientName)
            put("Version", "0.27.0")
            put("ClientName", "WiFi Audio Streaming")
            put("OS", System.getProperty("os.name") ?: "")
            put("Arch", System.getProperty("os.arch") ?: "")
            put("Instance", 1)
            put("ID", clientId)
            put("SnapStreamProtocolVersion", 2)
        })
        val frame = SnapcastWire.frame(
            SnapcastMessageType.HELLO, 0, 0, clock.now(), SnapcastTv(0, 0), payload
        )
        synchronized(out) { out.write(frame); out.flush() }
    }

    private fun sendTime(out: java.io.OutputStream) {
        val frame = SnapcastWire.frame(
            SnapcastMessageType.TIME, 0, 0, clock.now(), SnapcastTv(0, 0),
            SnapcastWire.timePayload(SnapcastTv(0, 0))
        )
        synchronized(out) { out.write(frame); out.flush() }
    }

    // ── Messaggi ───────────────────────────────────────────────────────────

    /**
     * Il server ci comunica volume, mute e buffer da usare.
     *
     * NON si risponde con CLIENT_INFO. Quel messaggio serve a segnalare una
     * modifica fatta SUL CLIENT (una manopola locale), non a confermare quello
     * che il server ha appena ordinato: il server lo interpreta come una
     * richiesta e adotta il valore ricevuto. Rimandarglielo indietro crea un
     * anello — server ordina, noi facciamo l'eco, il server adotta l'eco — e se
     * un SERVER_SETTINGS in volo porta ancora il valore vecchio, quel valore
     * vecchio torna al server e vince davvero. Non e' un problema estetico:
     * il volume si sposta sul serio.
     */
    private fun handleServerSettings(frame: SnapcastFrame) {
        val json = SnapJson.parse(SnapcastWire.readString(frame.payload))
        val bufferMs = json.intAt("bufferMs") ?: SnapcastDefaults.BUFFER_MS
        val latency = json.intAt("latency") ?: 0
        volumePercent = json.intAt("volume") ?: volumePercent
        muted = json.boolAt("muted") ?: muted

        val previous = status.bufferMs
        val effective = bufferMs + latency

        publish(status.copy(
            bufferMs = effective,
            volumePercent = volumePercent,
            muted = muted
        ))

        // Il buffer di riproduzione si fissa quando si apre la linea audio.
        // Se il server cambia buffer o latenza a stream avviato — ed e' quello
        // che succede muovendo il cursore della latenza nel mixer — senza
        // riaprire cambierebbe solo il numero mostrato, e l'audio resterebbe
        // dov'era. La soglia evita di riaprire per differenze irrilevanti.
        val dec = decoder
        if (dec != null && sink != null &&
            kotlin.math.abs(effective - previous) > BUFFER_CHANGE_THRESHOLD_MS) {
            reopenPlayer(dec, effective)
        }
        SnapLog.d("[Snapcast/stream] settings: buffer=${bufferMs}ms latency=${latency}ms " +
                     "volume=$volumePercent muted=$muted")
    }

    /**
     * Riapre l'uscita audio con un nuovo buffer.
     *
     * Costa un vuoto udibile, ma e' inevitabile: la profondita' del buffer di
     * riproduzione si decide all'apertura della linea. Del resto chi muove la
     * latenza si aspetta che qualcosa cambi.
     */
    private fun reopenPlayer(dec: SnapcastAudioDecoder, bufferMs: Int) {
        SnapLog.d("[Snapcast/stream] buffer ${status.bufferMs}ms -> ${bufferMs}ms: riapro la linea")
        runCatching { sink?.close() }
        val out = openPlayer(dec.sampleRate, dec.channels, bufferMs.coerceAtLeast(80))
        sink = out
        if (out == null) {
            publish(status.copy(state = SnapStreamState.ERROR, errorKey = "snap_err_output"))
        }
    }

    private fun handleCodecHeader(frame: SnapcastFrame) {
        val codec = SnapcastWire.readString(frame.payload)
        val nameLen = 4 + codec.toByteArray(Charsets.UTF_8).size
        if (frame.payload.size < nameLen + 4) return
        val headerLen = readLe32(frame.payload, nameLen)
        val header = frame.payload.copyOfRange(nameLen + 4,
            (nameLen + 4 + headerLen).coerceAtMost(frame.payload.size))

        SnapLog.d("[Snapcast/stream] codec=$codec header=${header.size}B")

        runCatching { decoder?.close() }
        val dec = SnapcastAudioDecoder.create(codec, header)
        if (dec == null) {
            publish(status.copy(state = SnapStreamState.ERROR,
                                codec = codec, errorKey = "snap_err_codec", errorDetail = codec))
            return
        }
        decoder = dec

        runCatching { sink?.close() }
        val out = openPlayer(dec.sampleRate, dec.channels, status.bufferMs.coerceAtLeast(80))
        if (out == null) {
            publish(status.copy(state = SnapStreamState.ERROR, errorKey = "snap_err_output"))
            return
        }
        sink = out
        publish(status.copy(
            state = SnapStreamState.BUFFERING,
            codec = codec,
            sampleRate = dec.sampleRate,
            channels = dec.channels
        ))
    }

    private var lastStatusPublish = 0L

    private fun handleWireChunk(frame: SnapcastFrame) {
        val dec = decoder ?: return
        val out = sink ?: return
        if (frame.payload.size < 12) return

        // payload: sec(4) usec(4) size(4) audio
        val sec = readLe32(frame.payload, 0)
        val usec = readLe32(frame.payload, 4)
        val size = readLe32(frame.payload, 8)
        if (size <= 0 || 12 + size > frame.payload.size) return

        val chunkServerMicros = sec.toLong() * 1_000_000L + usec.toLong()
        val pcm = dec.decode(frame.payload, 12, size) ?: return
        if (pcm.isEmpty()) return

        // Il guadagno si applica qui, non nel buffer di riproduzione: cosi'
        // anche lo spettro mostra quello che si sente davvero.
        applyVolume(pcm)

        out.submit(pcm)
        onPcm?.let { cb ->
            val shorts = ShortArray(pcm.size / 2)
            java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer().get(shorts)
            cb(shorts)
        }

        // Errore di sincronizzazione: quando il blocco DOVREBBE suonare, meno
        // quando in effetti suonera' dato quel che c'e' gia' in coda.
        val now = clock.nowMicros()
        val shouldPlayAtLocal = sync.serverToLocal(chunkServerMicros) + status.bufferMs * 1000L
        val willPlayAtLocal = now + out.bufferedMs() * 1000L
        val errMs = (willPlayAtLocal - shouldPlayAtLocal) / 1000.0

        val wall = System.currentTimeMillis()
        if (wall - lastStatusPublish > 400) {
            lastStatusPublish = wall
            publish(status.copy(
                state = if (sync.samplesSeen > 0) SnapStreamState.PLAYING else SnapStreamState.BUFFERING,
                clockOffsetMs = sync.offsetMicros / 1000.0,
                syncErrorMs = if (sync.samplesSeen > 0) errMs else 0.0,
                playoutBufferMs = out.bufferedMs()
            ))
        }
    }

    private fun handleTime(frame: SnapcastFrame, receivedMicros: Long) {
        // c1 = quando abbiamo spedito, tornato indietro nell'header
        val c1 = frame.header.received.toMicros().takeIf { it != 0L }
            ?: frame.header.sent.toMicros()
        val serverLatency = SnapcastWire.parseTimePayload(frame.payload).toMicros()
        val s2 = frame.header.sent.toMicros()
        sync.update(c1, serverLatency, s2, receivedMicros)
    }

    /**
     * Scala i campioni secondo volume e mute decisi dal server.
     *
     * La curva e' cubica, non lineare, perche' e' quella che usa snapclient per
     * il mixaggio software: se questo client usasse una curva diversa, lo stesso
     * "50%" suonerebbe piu' forte o piu' piano che nelle altre stanze, e in un
     * impianto multi-room e' esattamente cio' che non deve succedere.
     */
    private fun applyVolume(pcm: ByteArray) {
        val gain = if (muted) 0.0f else gainFor(volumePercent)
        if (gain >= 0.999f) return

        val bb = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 1 < pcm.size) {
            val scaled = (bb.getShort(i) * gain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bb.putShort(i, scaled.toShort())
            i += 2
        }
    }

    private fun readLe32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)

    companion object {
        /**
         * Percentuale del server → fattore di guadagno.
         *
         * L'esponente e' l'unico numero da toccare se il volume di questo
         * client non corrisponde a orecchio a quello degli altri: 3 e' la curva
         * di snapclient, 1 sarebbe una scala lineare.
         */
        /** Sotto questa differenza non vale la pena riaprire la linea audio. */
        const val BUFFER_CHANGE_THRESHOLD_MS = 20

        const val VOLUME_CURVE_EXPONENT = 3.0

        fun gainFor(percent: Int): Float {
            val p = percent.coerceIn(0, 100) / 100.0
            return Math.pow(p, VOLUME_CURVE_EXPONENT).toFloat()
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  Decodifica
 * ═══════════════════════════════════════════════════════════════════════════*/

/** Trasforma i blocchi del server in PCM 16 bit little endian. */
abstract class SnapcastAudioDecoder(val sampleRate: Int, val channels: Int) {
    abstract fun decode(data: ByteArray, offset: Int, length: Int): ByteArray?
    open fun close() {}

    companion object {
        fun create(codec: String, header: ByteArray): SnapcastAudioDecoder? = when {
            codec.equals("pcm", true) -> SnapcastPcmDecoder.fromWavHeader(header)
            codec.equals("flac", true) -> SnapcastFlacDecoder.create(header)
            else -> null
        }
    }
}

/** PCM: i blocchi sono gia' campioni, serve solo sapere formato e canali. */
class SnapcastPcmDecoder(sampleRate: Int, channels: Int, private val bits: Int) :
    SnapcastAudioDecoder(sampleRate, channels) {

    override fun decode(data: ByteArray, offset: Int, length: Int): ByteArray? {
        if (bits == 16) return data.copyOfRange(offset, offset + length)
        // Snapcast usa 16 bit in pratica; gli altri formati si riducono a 16.
        if (bits == 24) {
            val frames = length / 3
            val out = ByteArray(frames * 2)
            for (i in 0 until frames) {
                val v = ((data[offset + i * 3 + 2].toInt() and 0xFF) shl 8) or
                        (data[offset + i * 3 + 1].toInt() and 0xFF)
                out[i * 2] = (v and 0xFF).toByte()
                out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return out
        }
        if (bits == 32) {
            val frames = length / 4
            val out = ByteArray(frames * 2)
            for (i in 0 until frames) {
                out[i * 2] = data[offset + i * 4 + 2]
                out[i * 2 + 1] = data[offset + i * 4 + 3]
            }
            return out
        }
        return null
    }

    companion object {
        /**
         * Il codec header del PCM e' un header WAV: si leggono frequenza,
         * canali e bit dal chunk "fmt ". Cercarlo invece di assumere offset
         * fissi, perche' non tutti i server scrivono lo stesso preambolo.
         */
        fun fromWavHeader(header: ByteArray): SnapcastPcmDecoder? {
            if (header.size < 20) return null
            var i = 12
            while (i + 8 <= header.size) {
                val id = String(header, i, 4, Charsets.US_ASCII)
                val size = le32(header, i + 4)
                if (id == "fmt ") {
                    if (i + 8 + 16 > header.size) return null
                    val channels = le16(header, i + 10)
                    val rate = le32(header, i + 12)
                    val bits = le16(header, i + 22)
                    if (rate <= 0 || channels !in 1..8) return null
                    return SnapcastPcmDecoder(rate, channels, bits)
                }
                if (size <= 0) break
                i += 8 + size + (size and 1)
            }
            return null
        }

        private fun le16(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        private fun le32(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    }
}

/**
 * FLAC con il decodificatore di sistema.
 *
 * Snapcast manda come codec header l'intestazione FLAC — "fLaC" piu' il blocco
 * STREAMINFO — ed e' esattamente quello che MediaCodec vuole come csd-0. Da
 * STREAMINFO si leggono anche frequenza e canali, che servono per aprire la
 * linea di uscita prima ancora che arrivi il primo blocco.
 *
 * La decodifica non e' sincrona: si mette dentro un blocco e si tira fuori
 * quello che e' pronto, che all'inizio puo' essere niente. Un paio di blocchi
 * persi all'avvio non si sentono; accumularli in attesa che il decodificatore
 * risponda, invece, si sentirebbe eccome.
 */
class SnapcastFlacDecoder private constructor(
    sampleRate: Int,
    channels: Int,
    private val codec: MediaCodec
) : SnapcastAudioDecoder(sampleRate, channels) {

    private val info = MediaCodec.BufferInfo()
    @Volatile private var broken = false

    override fun decode(data: ByteArray, offset: Int, length: Int): ByteArray? {
        if (broken) return null
        return runCatching { pump(data, offset, length) }.getOrElse {
            // Un decodificatore in errore non torna piu' indietro: si smette di
            // interrogarlo invece di riprovare a ogni blocco.
            SnapLog.d("[Snapcast/flac] decodifica interrotta: ${it.message}")
            broken = true
            null
        }
    }

    private fun pump(data: ByteArray, offset: Int, length: Int): ByteArray? {
        val inIdx = codec.dequeueInputBuffer(DEQUEUE_US)
        if (inIdx >= 0) {
            val buf = codec.getInputBuffer(inIdx)
            if (buf != null) {
                buf.clear()
                buf.put(data, offset, length)
                codec.queueInputBuffer(inIdx, 0, length, 0L, 0)
            }
        } else {
            // Nessun posto libero: il decodificatore e' indietro. Si scarta
            // questo blocco, perche' metterlo in coda vorrebbe dire far
            // crescere un ritardo che poi non rientra.
            SnapLog.d("[Snapcast/flac] decodificatore occupato: blocco scartato")
        }

        var out: java.io.ByteArrayOutputStream? = null
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_US)
            if (outIdx >= 0) {
                if (info.size > 0) {
                    val ob = codec.getOutputBuffer(outIdx)
                    if (ob != null) {
                        val chunk = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.get(chunk)
                        if (out == null) out = java.io.ByteArrayOutputStream(info.size * 2)
                        out.write(chunk)
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                continue
            }
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                SnapLog.d("[Snapcast/flac] formato di uscita: ${codec.outputFormat}")
                continue
            }
            break
        }
        return out?.toByteArray()
    }

    override fun close() {
        broken = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
        /** Quanto si aspetta un buffer prima di lasciar perdere questo giro. */
        private const val DEQUEUE_US = 4_000L

        fun create(header: ByteArray): SnapcastFlacDecoder? {
            val info = readStreamInfo(header) ?: run {
                SnapLog.d("[Snapcast/flac] intestazione non leggibile (${header.size}B)")
                return null
            }
            val (rate, channels) = info
            return runCatching {
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, rate, channels)
                // Il csd-0 e' l'intestazione FLAC cosi' com'e' arrivata.
                fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(header))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }
                val mc = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
                mc.configure(fmt, null, null, 0)
                mc.start()
                SnapLog.d("[Snapcast/flac] aperto: $rate Hz, $channels ch")
                SnapcastFlacDecoder(rate, channels, mc)
            }.getOrElse {
                SnapLog.d("[Snapcast/flac] MediaCodec non parte: ${it.message}")
                null
            }
        }

        /**
         * Frequenza e canali dal blocco STREAMINFO.
         *
         * Dopo "fLaC" (4 byte) e l'intestazione del blocco (4 byte) ci sono le
         * dimensioni di blocco e frame (10 byte); poi, impacchettati nei bit:
         * 20 per la frequenza, 3 per i canali meno uno, 5 per i bit per
         * campione meno uno.
         */
        private fun readStreamInfo(h: ByteArray): Pair<Int, Int>? {
            if (h.size < 22) return null
            if (h[0] != 'f'.code.toByte() || h[1] != 'L'.code.toByte() ||
                h[2] != 'a'.code.toByte() || h[3] != 'C'.code.toByte()
            ) return null
            val rate = ((h[18].toInt() and 0xFF) shl 12) or
                    ((h[19].toInt() and 0xFF) shl 4) or
                    ((h[20].toInt() and 0xFF) shr 4)
            val channels = ((h[20].toInt() shr 1) and 0x07) + 1
            if (rate !in 8_000..192_000 || channels !in 1..2) {
                SnapLog.d("[Snapcast/flac] STREAMINFO fuori scala: $rate Hz, $channels ch")
                return null
            }
            return rate to channels
        }
    }
}

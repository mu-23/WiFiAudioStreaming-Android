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
 * PcmPlaybackSink.kt
 *
 * L'uscita audio per i flussi che non sono WFAS: Snapcast e, piu' avanti, RTP.
 *
 * Il client WFAS ha il suo percorso dentro NetworkManager, cucito addosso al
 * suo protocollo — numeri di sequenza, posizione del campione, pacchetti di
 * silenzio. Gli altri protocolli quelle cose non ce l'hanno o le hanno diverse,
 * e a loro serve solo un posto dove versare PCM a 16 bit little endian.
 *
 * Quel posto e' questo, ed e' l'unica parte della riproduzione che cambia fra
 * desktop e Android: la' e' javax.sound, qui e' AudioTrack. Tutto il resto —
 * decodifica, sincronismo, canale di controllo — e' lo stesso file sulle due
 * app.
 *
 * La mascheratura dei buchi non e' riscritta qui: e' quella del client WFAS,
 * che sceglie la giuntura dove si sente meno invece di ripetere e basta. Due
 * copie della stessa idea diventerebbero due comportamenti diversi al primo
 * ritocco.
 */

package com.cuscus.wifiaudiostreaming

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Dove finisce il PCM decodificato.
 *
 * Interfaccia e non classe perche' le prove non devono aprire una scheda
 * audio, e perche' il codice che decodifica non deve sapere niente di come si
 * suona.
 */
interface PcmPlaybackSink {
    /** PCM 16 bit little endian, interleaved. */
    fun submit(pcmLittleEndian: ByteArray)

    /**
     * Copre un buco di circa [approxBytes] byte.
     *
     * "Circa" perche' chi chiama sa quanto tempo e' mancato, non quanti byte
     * esatti: l'allineamento al frame lo fa questo lato.
     */
    fun conceal(approxBytes: Int)

    /** Quanto audio e' in coda, in millisecondi. Se scende a zero, si sentira'. */
    fun bufferedMs(): Int

    fun close()
}

/**
 * Apre una linea di uscita e la avvolge in un [PcmPlaybackSink].
 *
 * @param bufferMs il riempimento che si vuole tenere. Su Snapcast lo decide il
 *   server ed e' generoso (un secondo di serie), perche' li' conta stare in
 *   sincrono fra stanze piu' che rispondere in fretta.
 */
fun openPcmPlaybackSink(sampleRate: Int, channels: Int, bufferMs: Int, tag: String = "PCM-OUT"): PcmPlaybackSink? {
    val ch = channels.coerceIn(1, 2)
    val channelMask = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
    val frameSize = ch * 2

    val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuffer == AudioTrack.ERROR || minBuffer == AudioTrack.ERROR_BAD_VALUE) {
        Log.e(tag, "AudioTrack non regge ${sampleRate}Hz/${ch}ch")
        return null
    }

    // Come per il client WFAS: la coda deve poter assorbire una raffica senza
    // bloccare la write, o l'arretrato migra dentro il socket dove non si vede
    // e non si corregge.
    val target = bufferMs.coerceIn(40, 4000)
    val headroomFrames = sampleRate * 200 / 1000
    var size = minBuffer.coerceAtLeast(((target * sampleRate / 1000) + headroomFrames) * frameSize)
    if (size % frameSize != 0) size += frameSize - (size % frameSize)

    val track = runCatching {
        val b = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        b.build()
    }.getOrElse {
        Log.e(tag, "AudioTrack non si apre: ${it.message}")
        return null
    }

    return AudioTrackSink(track, sampleRate, ch, target, tag)
}

private class AudioTrackSink(
    private val track: AudioTrack,
    private val sampleRate: Int,
    private val channels: Int,
    private val targetMs: Int,
    private val tag: String
) : PcmPlaybackSink {

    private val frameSize = (channels * 2).coerceAtLeast(2)
    private val playout = PlayoutGovernor(track, sampleRate, frameSize, targetMs, tag)

    /** L'ultimo audio buono, da cui si ricava quello finto quando manca. */
    private var lastGood: ByteArray? = null

    /** La coda del riempimento, da dissolvere dentro il primo audio vero che torna. */
    private var concealTail: ByteArray? = null

    /** Oltre questo, il buco e' troppo lungo perche' inventarlo abbia senso. */
    private val maxConcealBytes = MAX_CONCEAL_MS * sampleRate / 1000 * frameSize

    /*
     * Prima di suonare si riempie.
     *
     * In Snapcast il buffer non e' un dettaglio di implementazione: e' quanto
     * in anticipo il server programma la riproduzione, uguale per tutte le
     * stanze, ed e' l'unica cosa che le tiene in sincrono. Cominciare a suonare
     * appena arriva il primo blocco vuol dire suonare un secondo prima di
     * tutti gli altri — e vuol dire anche che la coda resta vuota, per cui il
     * governatore della riproduzione passerebbe la vita a tirare la velocita'
     * al limite cercando di riempire qualcosa che non si riempie mai. Si sente
     * come una stonatura costante, e non si capisce da dove venga.
     */
    private val prebufferBytes = targetMs.toLong() * sampleRate / 1000L * frameSize
    private val pending = java.io.ByteArrayOutputStream()
    private var started = false

    @Volatile private var closed = false

    override fun submit(pcmLittleEndian: ByteArray) {
        if (closed) return
        val len = pcmLittleEndian.size - (pcmLittleEndian.size % frameSize)
        if (len <= 0) return

        if (!started) {
            pending.write(pcmLittleEndian, 0, len)
            if (pending.size() < prebufferBytes) return
            val filled = pending.toByteArray()
            pending.reset()
            started = true
            runCatching { track.play() }
            Log.d(tag, "riempimento fatto: ${filled.size / frameSize * 1000 / sampleRate} ms, parte")
            // write() conta gia' quel che ha scritto: il governatore parte
            // sapendo di avere la coda piena, che e' il suo punto di
            // riferimento. Azzerarla qui gli farebbe credere di essere a secco
            // subito dopo averla riempita.
            write(filled, filled.size)
            lastGood = filled.copyOfRange((filled.size - len).coerceAtLeast(0), filled.size)
            return
        }

        if (playout.hardResyncIfNeeded()) concealTail = null
        // Se l'arretrato e' fuori scala si scarta questo blocco: intervenire
        // adesso costa un salto, non intervenire costa una latenza che non
        // rientra piu'.
        if (playout.shouldDrop(len)) return

        val tail = concealTail
        val out = if (tail != null) {
            concealTail = null
            NetworkManager.crossfadeIntoReal(pcmLittleEndian, 0, len, tail, frameSize)
        } else {
            pcmLittleEndian
        }

        write(out, len)

        val keep = lastGood
        if (keep == null || keep.size != len) lastGood = ByteArray(len)
        System.arraycopy(out, 0, lastGood!!, 0, len)
        playout.retune()
    }

    override fun conceal(approxBytes: Int) {
        // Finche' non si e' partiti non c'e' niente da mascherare: il buco lo
        // assorbe il riempimento, che e' esattamente il suo mestiere.
        if (closed || !started) return
        val wanted = approxBytes.coerceIn(0, maxConcealBytes)
        if (wanted < frameSize) return
        val ref = lastGood
        if (ref == null) {
            writeSilence(wanted)
            concealTail = null
            return
        }
        val block = NetworkManager.rampedConceal(ref, wanted, frameSize)
        if (block == null) {
            writeSilence(wanted)
            concealTail = null
            return
        }
        val body = wanted - (wanted % frameSize)
        write(block, body)
        // Quel che avanza e' la dissolvenza: aspetta il prossimo audio vero.
        concealTail = block.copyOfRange(body, block.size)
    }

    override fun bufferedMs(): Int = when {
        closed -> 0
        // Durante il riempimento la coda vera e' quella in memoria: dirlo bene
        // serve a chi calcola l'errore di sincronismo.
        !started -> (pending.size().toLong() / frameSize * 1000L / sampleRate).toInt()
        else -> playout.bufferedMs()
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.reset()
        runCatching { com.cuscus.wifiaudiostreaming.dsp.AmbientSpectrumAnalyzer.reset() }
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private fun write(buf: ByteArray, len: Int) {
        val n = runCatching { track.write(buf, 0, len, AudioTrack.WRITE_BLOCKING) }.getOrDefault(0)
        if (n > 0) {
            playout.noteWritten(n)
            // Lo stesso audio che va all'altoparlante alimenta lo sfondo che
            // respira: e' quel che fa il client WFAS, e senza, la schermata di
            // ascolto resterebbe immobile mentre la musica suona.
            runCatching {
                com.cuscus.wifiaudiostreaming.dsp.AmbientSpectrumAnalyzer
                    .feedFrame(buf, 0, n, channels, sampleRate)
            }
        }
    }

    private fun writeSilence(bytes: Int) {
        val n = bytes - (bytes % frameSize)
        if (n <= 0) return
        write(ByteArray(n), n)
    }

    private companion object {
        /** Duecento millisecondi: oltre, il finto si sente piu' del buco. */
        const val MAX_CONCEAL_MS = 200
    }
}

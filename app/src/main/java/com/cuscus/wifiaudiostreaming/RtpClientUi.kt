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
 * RtpClientUi.kt
 *
 * La sezione Ricevi per RTP.
 *
 * Quattro modi di dire la stessa cosa — un file .sdp, un descrittore incollato,
 * i campi a mano, una sorgente salvata — e un modulo solo dove finiscono tutti.
 * Importare non porta da nessuna parte: riempie i campi che stai guardando, e
 * da li' li puoi correggere. E' la differenza fra un'importazione che ti fa
 * capire cosa ha capito e una che ti chiede di fidarti.
 *
 * Quel che il descrittore non dice viene detto come avviso, non nascosto: una
 * frequenza sbagliata non produce un errore, produce audio che suona alla
 * velocita' sbagliata, ed e' il tipo di guasto che si incolpa sempre di
 * qualcos'altro.
 */

package com.cuscus.wifiaudiostreaming

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cuscus.wifiaudiostreaming.data.AppSettings
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import com.cuscus.wifiaudiostreaming.rtp.RtpSdp
import com.cuscus.wifiaudiostreaming.rtp.RtpSession
import com.cuscus.wifiaudiostreaming.rtp.RtpSource
import com.cuscus.wifiaudiostreaming.rtp.RtpSourceOrigin
import com.cuscus.wifiaudiostreaming.rtp.RtpState
import kotlinx.coroutines.launch

/** Il colore della famiglia RTP: un'altra ancora, e va detto col colore. */
@Composable
private fun rtpAccent(): Color = MaterialTheme.colorScheme.secondary

/**
 * Le chiavi che il parser e la validazione restituiscono, tradotte.
 *
 * Il codice di rete e' lo stesso del desktop e non sa niente di risorse
 * Android: parla per chiavi, e la traduzione sta tutta qui.
 */
@Composable
private fun rtpMessage(key: String, arg: String? = null): String = when (key) {
    "sdp_err_empty" -> stringResource(R.string.sdp_err_empty)
    "sdp_err_no_media" -> stringResource(R.string.sdp_err_no_media)
    "sdp_err_no_audio" -> stringResource(R.string.sdp_err_no_audio)
    "sdp_err_port_zero" -> stringResource(R.string.sdp_err_port_zero)
    "sdp_warn_proto" -> stringResource(R.string.sdp_warn_proto)
    "sdp_warn_multi_media" -> stringResource(R.string.sdp_warn_multi_media)
    "sdp_warn_no_connection" -> stringResource(R.string.sdp_warn_no_connection)
    "sdp_warn_no_rtpmap" -> stringResource(R.string.sdp_warn_no_rtpmap)
    "sdp_warn_no_rate" -> stringResource(R.string.sdp_warn_no_rate)
    "sdp_warn_channels" -> stringResource(R.string.sdp_warn_channels)
    "rtp_val_port" -> stringResource(R.string.rtp_val_port)
    "rtp_val_rate" -> stringResource(R.string.rtp_val_rate)
    "rtp_val_channels" -> stringResource(R.string.rtp_val_channels)
    "rtp_val_pt" -> stringResource(R.string.rtp_val_pt)
    "rtp_val_address" -> stringResource(R.string.rtp_val_address)
    "rtp_err_no_output" -> stringResource(R.string.rtp_err_no_output)
    "rtp_err_socket" -> stringResource(R.string.rtp_err_socket)
    "rtp_err_codec_unsupported" -> stringResource(R.string.rtp_err_codec_unsupported, arg ?: "?")
    else -> key
}

internal fun startRtpSession(context: Context, source: RtpSource, settings: AppSettings) {
    RtpSession.start(context, source, settings.latencyMs, settings.networkInterface)
    ContextCompat.startForegroundService(context, Intent(context, RtpClientService::class.java))
}

internal fun stopRtpSession(context: Context) {
    RtpSession.stop()
    context.stopService(Intent(context, RtpClientService::class.java))
}

// ─────────────────────────────────────────────────────────────────────────────
// La card: quattro modi di indicare una sorgente, un modulo solo
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RtpClientSection(appSettings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val accent = rtpAccent()

    val saved = remember(appSettings.rtpSources) {
        appSettings.rtpSources.mapNotNull { RtpSource.deserialize(it) }
    }

    // Il modulo: e' sempre questo, comunque sia arrivata la configurazione.
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("9094") }
    var codec by remember { mutableStateOf("L16") }
    var rate by remember { mutableStateOf("48000") }
    var channels by remember { mutableStateOf("2") }
    var payload by remember { mutableStateOf("96") }
    var sdpText by remember { mutableStateOf<String?>(null) }

    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }

    fun fill(src: RtpSource, raw: String?, warns: List<String>) {
        name = src.name
        address = src.address
        port = src.port.toString()
        codec = src.encoding
        rate = src.sampleRate.toString()
        channels = src.channels.toString()
        payload = src.payloadType.toString()
        sdpText = raw
        warnings = warns
        parseError = null
    }

    fun readSdp(raw: String) {
        val result = RtpSdp.parse(raw)
        val src = result.source
        if (src == null) {
            parseError = result.error ?: "sdp_err_empty"
            warnings = emptyList()
            return
        }
        fill(src, raw, result.warnings)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) parseError = "sdp_err_empty" else readSdp(text)
    }

    fun current(): RtpSource = RtpSource(
        name = name.trim(),
        address = address.trim(),
        port = port.trim().toIntOrNull() ?: 0,
        payloadType = payload.trim().toIntOrNull() ?: 96,
        encoding = codec.trim().ifBlank { "L16" },
        sampleRate = rate.trim().toIntOrNull() ?: 0,
        channels = channels.trim().toIntOrNull() ?: 0,
        origin = if (sdpText != null) RtpSourceOrigin.SDP_FILE else RtpSourceOrigin.MANUAL,
        sdpText = sdpText
    )

    val problems = remember(address, port, codec, rate, channels, payload) {
        RtpSdp.validate(current())
    }

    fun persist(list: List<RtpSource>) {
        scope.launch { SettingsDataStore(context).saveRtpSources(list.map { it.serialize() }) }
    }

    val notices = buildList {
        parseError?.let { add(it to true) }
        warnings.distinct().forEach { add(it to false) }
        problems.forEach { add(it to true) }
    }
    // L16 e' l'unico che si riproduce qui: dirlo prima di premere Ascolta,
    // non dopo, quando la porta e' gia' aperta e non arriva niente.
    val codecUnsupported = !current().isNativePcm && codec.isNotBlank()

    ExpressiveExpandableCard(
        icon = Icons.Outlined.Radio,
        // Un raggio che si apre: e' quel che fa RTP, manda a chi ascolta senza
        // chiedere niente a nessuno.
        openShape = MaterialShapes.Sunny,
        title = stringResource(R.string.rtp_client_title),
        subtitle = stringResource(R.string.rtp_client_subtitle),
        accent = accent,
        stateKey = "rtp-client-card",
        badge = if (saved.isNotEmpty()) saved.size.toString() else null
    ) {
        // Un SDP e' la strada corta: lo si apre o lo si incolla, e i sei campi
        // qui sotto si riempiono da soli.
        ExpressiveReveal(0) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = {
                        haptics.tap()
                        // I .sdp arrivano con mimetype di ogni tipo, e spesso con
                        // nessuno: filtrare stretto vuol dire un selettore vuoto.
                        picker.launch(arrayOf("application/sdp", "text/plain", "*/*"))
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.rtp_import_file), fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(
                    onClick = { haptics.tap(); pasting = !pasting },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.rtp_paste), fontWeight = FontWeight.Bold)
                }
            }
        }

        AnimatedVisibility(
            visible = pasting,
            enter = expandVertically(tween(280, easing = FastOutSlowInEasing)) + fadeIn(tween(240)),
            exit = shrinkVertically(tween(200, easing = FastOutSlowInEasing)) + fadeOut(tween(120))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.rtp_paste_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text(stringResource(R.string.rtp_paste_title)) },
                    shape = RoundedCornerShape(20.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp)
                )
                FilledTonalButton(
                    onClick = {
                        haptics.confirm()
                        readSdp(pasted)
                        pasting = false
                    },
                    enabled = pasted.isNotBlank(),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.rtp_analyze), fontWeight = FontWeight.Bold) }
            }
        }

        // Quel che serve davvero sapere: dove ascoltare. Il resto ha un valore
        // di serie o si misura da solo, e sta ripiegato qui sotto.
        ExpressiveReveal(1) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.rtp_form_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text(stringResource(R.string.rtp_address)) },
                        placeholder = { Text(stringResource(R.string.rtp_address_ph)) },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.rtp_port)) },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        ExpressiveReveal(2) {
            ExpressiveFold(
                title = stringResource(R.string.rtp_fold_format),
                accent = accent,
                stateKey = "rtp-fold-format"
            ) {
                Text(
                    text = stringResource(R.string.rtp_fold_format_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.rtp_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = codec,
                        onValueChange = { codec = it },
                        label = { Text(stringResource(R.string.rtp_codec)) },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = rate,
                        onValueChange = { v -> rate = v.filter { it.isDigit() }.take(6) },
                        label = { Text(stringResource(R.string.rtp_rate)) },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = channels,
                        onValueChange = { v -> channels = v.filter { it.isDigit() }.take(1) },
                        label = { Text(stringResource(R.string.rtp_channels)) },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = payload,
                        onValueChange = { v -> payload = v.filter { it.isDigit() }.take(3) },
                        label = { Text(stringResource(R.string.rtp_payload)) },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (notices.isNotEmpty() || codecUnsupported) {
            ExpressiveReveal(3) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    notices.forEach { (key, isError) ->
                        RtpNotice(rtpMessage(key), error = isError)
                    }
                    if (codecUnsupported) {
                        RtpNotice(
                            rtpMessage("rtp_err_codec_unsupported", codec.trim()),
                            error = true
                        )
                    }
                }
            }
        }

        ExpressiveReveal(4) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = {
                        haptics.tap()
                        val src = current()
                        persist(saved.filterNot { it.address == src.address && it.port == src.port } + src)
                    },
                    enabled = problems.isEmpty(),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.rtp_save), fontWeight = FontWeight.Bold) }

                FilledTonalButton(
                    onClick = { haptics.confirm(); startRtpSession(context, current(), appSettings) },
                    enabled = problems.isEmpty() && current().isNativePcm,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.rtp_listen), fontWeight = FontWeight.Bold) }
            }
        }

        if (saved.isNotEmpty()) {
            ExpressiveReveal(5) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.rtp_saved_title).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp,
                        color = accent
                    )
                    saved.forEach { src ->
                        ExpressiveSavedRow(
                            icon = Icons.Outlined.Radio,
                            shape = MaterialShapes.Cookie7Sided,
                            title = src.displayName(),
                            subtitle = src.formatSummary(),
                            accent = accent,
                            forgetDescription = stringResource(R.string.rtp_forget),
                            onClick = { fill(src, src.sdpText, emptyList()) },
                            onForget = {
                                persist(saved.filterNot { it.address == src.address && it.port == src.port })
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RtpNotice(text: String, error: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (error) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sessione: cosa sta arrivando
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Il pannello che si vede mentre si ascolta.
 *
 * "In attesa" e "in ricezione" sono due stati distinti e restano distinti: in
 * RTP non c'e' nessuna stretta di mano, quindi un mittente spento e una porta
 * sbagliata producono la stessa identica schermata muta se non li si dichiara.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RtpSessionSection() {
    val accent = rtpAccent()

    val status by RtpSession.status.collectAsState()
    val source = status.source
    val playing = status.state == RtpState.PLAYING

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(accent.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    status.state == RtpState.WAITING ->
                        LoadingIndicator(modifier = Modifier.size(26.dp), color = accent)
                    playing ->
                        Icon(Icons.Outlined.GraphicEq, null, Modifier.size(24.dp), tint = accent)
                    else ->
                        Icon(Icons.Outlined.HourglassEmpty, null, Modifier.size(24.dp), tint = accent)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.rtp_sess_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(
                        when (status.state) {
                            RtpState.WAITING -> R.string.rtp_state_waiting
                            RtpState.PLAYING -> R.string.rtp_state_playing
                            RtpState.ERROR -> R.string.rtp_state_error
                            RtpState.IDLE -> R.string.rtp_state_idle
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (status.state == RtpState.ERROR)
                        MaterialTheme.colorScheme.error else accent
                )
            }
            // Qui non c'e' nessun pulsante per fermare: si ferma dalla forma
            // in alto, che e' dove si ferma qualunque sessione. Averne due che
            // fanno la stessa cosa a mezzo schermo di distanza non da' una
            // scelta, da' un dubbio su quale delle due sia quella vera.
        }

        // Il formato che si mostra e' quello che il flusso ha detto di essere,
        // non quello che era stato scritto nel modulo: mentre si ascolta conta
        // cosa sta suonando. Se i due non coincidono lo si dice sotto, perche'
        // altrimenti l'utente vede numeri che non ha mai messo e non capisce
        // da dove arrivino.
        val realRate = status.detectedSampleRate
        val realCh   = status.detectedChannels
        val corrected = source != null && realRate != null && realCh != null &&
                (realRate != source.sampleRate || realCh != source.channels)
        if (source != null) {
            RtpInfoRow(
                stringResource(R.string.rtp_lbl_source),
                if (source.address.isBlank()) ":${source.port}" else "${source.address}:${source.port}"
            )
            RtpInfoRow(
                stringResource(R.string.rtp_lbl_format),
                if (corrected)
                    "${source.encoding} $realRate Hz · ${if (realCh == 1) "mono" else "$realCh ch"}"
                else source.formatSummary()
            )
        }
        if (corrected && source != null) {
            RtpNotice(
                stringResource(
                    R.string.rtp_detected_note,
                    realRate ?: 0,
                    if ((realCh ?: 2) == 1) "mono" else "${realCh}ch",
                    source.sampleRate,
                    if (source.channels == 1) "mono" else "${source.channels}ch"
                ),
                error = false
            )
        }
        RtpInfoRow(
            stringResource(R.string.rtp_lbl_received),
            stringResource(R.string.rtp_stats, status.packets, status.bytes / 1024, status.lostPackets)
        )
        RtpInfoRow(
            stringResource(R.string.rtp_lbl_buffer),
            stringResource(R.string.rtp_buffer_ms, status.bufferMs) +
                    if (playing && status.bufferMs < 25) "  ·  " + stringResource(R.string.rtp_buffer_low) else ""
        )

        // Un buffer che continua a rasentare lo zero non e' un dettaglio da
        // esperti: e' la spiegazione degli scatti che si stanno sentendo.
        if (playing && status.bufferMs < 25) {
            RtpNotice(stringResource(R.string.rtp_buffer_hint), error = false)
        }

        status.errorKey?.let { key ->
            RtpNotice(rtpMessage(key, status.errorDetail), error = true)
        }

        // In attesa da un po' senza che arrivi niente: il consiglio dipende da
        // come si sta ascoltando, perche' le cause sono diverse.
        if (status.state == RtpState.WAITING && status.packets == 0L) {
            RtpNotice(
                stringResource(
                    if (source?.isMulticast == true) R.string.rtp_hint_multicast
                    else R.string.rtp_hint_firewall
                ),
                error = false
            )
        }
    }
}

@Composable
private fun RtpInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

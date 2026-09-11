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
 * SnapcastClientUi.kt
 *
 * La sezione Ricevi per Snapcast: ricerca, collegamento e mixer dell'impianto.
 *
 * I server Snapcast stanno nella stessa schermata dei server WFAS perche' per
 * chi guarda sono la stessa domanda — "a cosa mi collego" — ma devono
 * distinguersi a colpo d'occhio, o si finisce per provare a collegarsi con la
 * chiave WFAS a un impianto che non ne sa niente. La differenza la fa il
 * colore: i dispositivi WFAS usano l'accento dell'app, questi il terziario del
 * tema. E' il modo di Material di dire "un'altra famiglia" senza inventare
 * colori che poi litigano col tema dinamico.
 *
 * Il mixer mostra tutto l'impianto, non solo questo telefono: in un sistema
 * multiroom la domanda vera non e' mai "quanto sto suonando io" ma "quanto sta
 * suonando la cucina", e doverlo chiedere a un'altra app renderebbe questa
 * meta' di un lavoro.
 */

package com.cuscus.wifiaudiostreaming

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.cuscus.wifiaudiostreaming.data.AppSettings
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import com.cuscus.wifiaudiostreaming.snapcast.SnapClientInfo
import com.cuscus.wifiaudiostreaming.snapcast.SnapControlState
import com.cuscus.wifiaudiostreaming.snapcast.SnapControlStatus
import com.cuscus.wifiaudiostreaming.snapcast.SnapGroupInfo
import com.cuscus.wifiaudiostreaming.snapcast.SnapStreamState
import com.cuscus.wifiaudiostreaming.snapcast.SnapStreamStatus
import com.cuscus.wifiaudiostreaming.snapcast.SnapcastDefaults
import com.cuscus.wifiaudiostreaming.snapcast.SnapcastReceiver
import com.cuscus.wifiaudiostreaming.snapcast.SnapcastServerRef
import kotlinx.coroutines.launch

/** Il colore della famiglia Snapcast. Diverso dall'accento WFAS, e apposta. */
@Composable
private fun snapAccent(): Color = MaterialTheme.colorScheme.tertiary

// ─────────────────────────────────────────────────────────────────────────────
// Ricerca
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveSnapcastDiscoverySection(
    servers: List<SnapcastServerRef>,
    onConnect: (SnapcastServerRef) -> Unit
) {
    if (servers.isEmpty()) return
    val accent = snapAccent()

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            label = stringResource(R.string.snap_client_section_title),
            accent = accent,
            trailing = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = servers.size.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                        color = accent
                    )
                }
            }
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            servers.forEach { ref ->
                SnapcastServerRow(ref = ref, accent = accent, onConnect = { onConnect(ref) })
            }
        }
    }
}

@Composable
private fun SnapcastServerRow(
    ref: SnapcastServerRef,
    accent: Color,
    onConnect: () -> Unit
) {
    val haptics = rememberAppHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val corner by animateDpAsState(
        targetValue = if (pressed) 14.dp else 28.dp,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "SnapCorner"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "SnapScale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(interactionSource = interaction, indication = null) {
                haptics.confirm()
                onConnect()
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Niente forma morfata come per i dispositivi WFAS: un contenitore
        // squadrato dice "altra cosa" prima ancora che si legga il testo.
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(accent.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.GraphicEq,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = accent
            )
        }

        Spacer(Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = ref.displayName(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${ref.host}:${ref.streamPort}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = "SNAPCAST  ·  MULTIROOM",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }

        Spacer(Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(accent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.snap_connect),
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.surfaceContainerLowest
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Collegamento a mano
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveSnapcastConnectCard(
    saved: List<SnapcastServerRef>,
    onConnect: (SnapcastServerRef) -> Unit,
    onSave: (SnapcastServerRef) -> Unit,
    onForget: (SnapcastServerRef) -> Unit
) {
    val accent = snapAccent()
    val haptics = rememberAppHaptics()

    var host by remember { mutableStateOf("") }
    var streamPort by remember { mutableStateOf(SnapcastDefaults.STREAM_PORT.toString()) }
    var controlPort by remember { mutableStateOf(SnapcastDefaults.CONTROL_PORT.toString()) }
    var name by remember { mutableStateOf("") }

    fun build(): SnapcastServerRef? {
        val h = host.trim()
        if (h.isEmpty()) return null
        val sp = streamPort.trim().toIntOrNull() ?: SnapcastDefaults.STREAM_PORT
        val cp = controlPort.trim().toIntOrNull() ?: SnapcastDefaults.CONTROL_PORT
        if (sp !in 1..65535 || cp !in 1..65535) return null
        return SnapcastServerRef(
            name = name.trim(), host = h, streamPort = sp, controlPort = cp, discovered = false
        )
    }

    ExpressiveExpandableCard(
        icon = Icons.Outlined.Speaker,
        // Quattro foglie, quattro stanze: Snapcast e' l'unico dei tre che suona
        // in piu' posti insieme, e la forma lo dice prima del testo.
        openShape = MaterialShapes.Clover4Leaf,
        title = stringResource(R.string.snap_manual_title),
        subtitle = stringResource(R.string.snap_manual_subtitle),
        accent = accent,
        stateKey = "snap-manual-card",
        badge = if (saved.isNotEmpty()) saved.size.toString() else null
    ) {
        // L'indirizzo e' l'unica cosa che il server non puo' indovinare da solo:
        // le porte ce l'hanno di serie, il nome e' un vezzo.
        ExpressiveReveal(0) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(R.string.snap_host)) },
                placeholder = { Text("192.168.1.10") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
        }

        ExpressiveReveal(1) {
            ExpressiveFold(
                title = stringResource(R.string.snap_fold_details),
                accent = accent,
                stateKey = "snap-fold-details"
            ) {
                Text(
                    text = stringResource(R.string.snap_ports_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = streamPort,
                        onValueChange = { v -> streamPort = v.filter { it.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.snap_stream_port)) },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = controlPort,
                        onValueChange = { v -> controlPort = v.filter { it.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.snap_control_port)) },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.snap_rename)) },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        ExpressiveReveal(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { build()?.let { haptics.tap(); onSave(it) } },
                    enabled = host.isNotBlank(),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.snap_save), fontWeight = FontWeight.Bold) }

                FilledTonalButton(
                    onClick = { build()?.let { haptics.confirm(); onConnect(it) } },
                    enabled = host.isNotBlank(),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.snap_connect), fontWeight = FontWeight.Bold) }
            }
        }

        if (saved.isNotEmpty()) {
            ExpressiveReveal(3) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.snap_manual_saved_title).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp,
                        color = accent
                    )
                    saved.forEach { ref ->
                        ExpressiveSavedRow(
                            icon = Icons.Outlined.Speaker,
                            shape = MaterialShapes.Cookie4Sided,
                            title = ref.displayName(),
                            subtitle = "${ref.host}:${ref.streamPort}",
                            accent = accent,
                            forgetDescription = stringResource(R.string.snap_forget),
                            onClick = { onConnect(ref) },
                            onForget = { onForget(ref) }
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sessione: cosa sta suonando, e tutto l'impianto
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveSnapcastSessionPanel(
    stream: SnapStreamStatus,
    control: SnapControlStatus,
    selfClientId: String,
    splitSupported: Boolean,
    onSetVolume: (clientId: String, percent: Int, muted: Boolean) -> Unit,
    onSetName: (clientId: String, name: String) -> Unit,
    onSetLatency: (clientId: String, ms: Int) -> Unit,
    onGroupMute: (groupId: String, mute: Boolean) -> Unit,
    onGroupName: (groupId: String, name: String) -> Unit,
    onMoveClient: (clientId: String, groupId: String) -> Unit,
    onSplitClient: (clientId: String) -> Unit
) {
    val accent = snapAccent()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        SnapcastStreamCard(stream, accent)
        SnapcastMixer(
            control = control,
            accent = accent,
            selfClientId = selfClientId,
            splitSupported = splitSupported,
            onSetVolume = onSetVolume,
            onSetName = onSetName,
            onSetLatency = onSetLatency,
            onGroupMute = onGroupMute,
            onGroupName = onGroupName,
            onMoveClient = onMoveClient,
            onSplitClient = onSplitClient
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SnapcastStreamCard(
    stream: SnapStreamStatus,
    accent: Color
) {
    val server = stream.server

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
                if (stream.state == SnapStreamState.CONNECTING ||
                    stream.state == SnapStreamState.BUFFERING
                ) {
                    LoadingIndicator(modifier = Modifier.size(26.dp), color = accent)
                } else {
                    Icon(Icons.Outlined.GraphicEq, null, Modifier.size(24.dp), tint = accent)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.snap_sess_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(
                        when (stream.state) {
                            SnapStreamState.CONNECTING -> R.string.snap_state_connecting
                            SnapStreamState.BUFFERING -> R.string.snap_state_buffering
                            SnapStreamState.PLAYING -> R.string.snap_state_playing
                            SnapStreamState.ERROR -> R.string.snap_state_error
                            SnapStreamState.IDLE -> R.string.snap_state_idle
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (stream.state == SnapStreamState.ERROR)
                        MaterialTheme.colorScheme.error else accent
                )
            }
            // Niente pulsante per staccarsi qui: si stacca dalla forma in alto,
            // che e' dove si ferma qualunque sessione. Due modi di fare la
            // stessa cosa nella stessa schermata non sono una scelta in piu',
            // sono un dubbio su quale sia quello vero.
        }

        if (server != null) {
            SnapInfoRow(stringResource(R.string.snap_lbl_server), "${server.host}:${server.streamPort}")
        }
        if (stream.sampleRate > 0) {
            SnapInfoRow(
                stringResource(R.string.snap_lbl_format),
                "${stream.codec.uppercase()} · ${stream.sampleRate} Hz · ${stream.channels} ch"
            )
        }
        if (stream.bufferMs > 0) {
            SnapInfoRow(
                stringResource(R.string.snap_lbl_buffer),
                stringResource(R.string.snap_buffer_value, stream.bufferMs, stream.playoutBufferMs)
            )
        }
        if (stream.state == SnapStreamState.PLAYING) {
            SnapInfoRow(
                stringResource(R.string.snap_lbl_sync),
                stringResource(R.string.snap_sync_value, stream.syncErrorMs, stream.clockOffsetMs)
            )
        }
        stream.errorKey?.let { key ->
            Text(
                text = snapErrorText(key, stream.errorDetail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun snapErrorText(key: String, detail: String?): String {
    val base = when (key) {
        "snap_err_codec" -> stringResource(R.string.snap_err_codec, detail ?: "?")
        "snap_err_output" -> stringResource(R.string.snap_err_output)
        else -> stringResource(R.string.snap_err_stream)
    }
    return if (key != "snap_err_codec" && !detail.isNullOrBlank()) "$base $detail" else base
}

@Composable
private fun SnapInfoRow(label: String, value: String) {
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mixer
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SnapcastMixer(
    control: SnapControlStatus,
    accent: Color,
    selfClientId: String,
    splitSupported: Boolean,
    onSetVolume: (String, Int, Boolean) -> Unit,
    onSetName: (String, String) -> Unit,
    onSetLatency: (String, Int) -> Unit,
    onGroupMute: (String, Boolean) -> Unit,
    onGroupName: (String, String) -> Unit,
    onMoveClient: (String, String) -> Unit,
    onSplitClient: (String) -> Unit
) {
    val groups = control.status.groups
    val clientCount = control.status.allClients.size

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            label = stringResource(R.string.snap_mixer_title),
            accent = accent,
            trailing = {
                if (groups.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.snap_mixer_subtitle, clientCount, groups.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        when {
            control.state == SnapControlState.CONNECTING -> SnapMixerNotice(
                stringResource(R.string.snap_mixer_connecting), accent, loading = true
            )
            control.state == SnapControlState.ERROR -> {
                SnapMixerNotice(stringResource(R.string.snap_mixer_error), accent, loading = false)
                SnapControlDiagnostics(control)
            }
            control.state == SnapControlState.IDLE -> SnapMixerNotice(
                stringResource(R.string.snap_mixer_idle), accent, loading = false
            )
            groups.isEmpty() -> SnapMixerNotice(
                stringResource(R.string.snap_mixer_empty), accent, loading = false
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!splitSupported) {
                    Text(
                        text = stringResource(R.string.snap_split_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                groups.forEach { group ->
                    SnapGroupBlock(
                        group = group,
                        allGroups = groups,
                        accent = accent,
                        selfClientId = selfClientId,
                        splitSupported = splitSupported,
                        onSetVolume = onSetVolume,
                        onSetName = onSetName,
                        onSetLatency = onSetLatency,
                        onGroupMute = onGroupMute,
                        onGroupName = onGroupName,
                        onMoveClient = onMoveClient,
                        onSplitClient = onSplitClient
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SnapMixerNotice(text: String, accent: Color, loading: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            LoadingIndicator(modifier = Modifier.size(24.dp), color = accent)
            Spacer(Modifier.width(14.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Perche' il canale di controllo non risponde.
 *
 * "Irraggiungibile" da solo non distingue un rifiuto della connessione da un
 * timeout o da una caduta dopo l'apertura: sono tre problemi con tre soluzioni
 * diverse, e chi legge deve poterli distinguere senza collegare il telefono al
 * computer.
 */
@Composable
private fun SnapControlDiagnostics(control: SnapControlStatus) {
    Spacer(Modifier.height(10.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.snap_ctrl_detail_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        SnapInfoRow(stringResource(R.string.snap_ctrl_diag_target), "${control.host}:${control.port}")
        SnapInfoRow(stringResource(R.string.snap_ctrl_diag_attempts), control.attempts.toString())
        Text(
            text = control.errorDetail ?: stringResource(R.string.snap_ctrl_no_detail),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.snap_mixer_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SnapGroupBlock(
    group: SnapGroupInfo,
    allGroups: List<SnapGroupInfo>,
    accent: Color,
    selfClientId: String,
    splitSupported: Boolean,
    onSetVolume: (String, Int, Boolean) -> Unit,
    onSetName: (String, String) -> Unit,
    onSetLatency: (String, Int) -> Unit,
    onGroupMute: (String, Boolean) -> Unit,
    onGroupName: (String, String) -> Unit,
    onMoveClient: (String, String) -> Unit,
    onSplitClient: (String) -> Unit
) {
    val haptics = rememberAppHaptics()
    var renaming by remember(group.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Workspaces, null, Modifier.size(20.dp), tint = accent)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.displayName(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = group.streamId.ifBlank { stringResource(R.string.snap_no_stream) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { haptics.tap(); renaming = true }) {
                Icon(
                    Icons.Outlined.DriveFileRenameOutline,
                    contentDescription = stringResource(R.string.snap_group_rename),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalIconButton(
                onClick = { haptics.toggle(!group.muted); onGroupMute(group.id, !group.muted) },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (group.muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                    contentDescription = stringResource(R.string.snap_group_mute),
                    modifier = Modifier.size(18.dp),
                    tint = if (group.muted) MaterialTheme.colorScheme.error else accent
                )
            }
        }

        if (renaming) {
            SnapTextPrompt(
                label = stringResource(R.string.snap_group_rename),
                initial = group.name,
                onDismiss = { renaming = false },
                onConfirm = { renaming = false; onGroupName(group.id, it) }
            )
        }

        if (group.clients.isEmpty()) {
            Text(
                text = stringResource(R.string.snap_group_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            group.clients.forEach { client ->
                SnapClientRow(
                    client = client,
                    groupMuted = group.muted,
                    otherGroups = allGroups.filter { it.id != group.id },
                    accent = accent,
                    isSelf = client.id == selfClientId,
                    // Staccarlo ha senso solo se non e' gia' solo; e per noi
                    // stessi si puo' sempre, perche' basta ripresentarsi.
                    canSplit = group.clients.size > 1 &&
                            (client.id == selfClientId || splitSupported),
                    onSetVolume = onSetVolume,
                    onSetName = onSetName,
                    onSetLatency = onSetLatency,
                    onMoveClient = onMoveClient,
                    onSplitClient = onSplitClient
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SnapClientRow(
    client: SnapClientInfo,
    groupMuted: Boolean,
    otherGroups: List<SnapGroupInfo>,
    accent: Color,
    isSelf: Boolean,
    canSplit: Boolean,
    onSetVolume: (String, Int, Boolean) -> Unit,
    onSetName: (String, String) -> Unit,
    onSetLatency: (String, Int) -> Unit,
    onMoveClient: (String, String) -> Unit,
    onSplitClient: (String) -> Unit
) {
    val haptics = rememberAppHaptics()

    /*
     * Il cursore mentre lo si trascina.
     *
     * Il server risponde con la sua notifica, ma nel frattempo puo' anche
     * spedire una fotografia scattata prima del comando: applicarla farebbe
     * saltare il pomello sotto il dito. Finche' si trascina comanda il dito;
     * appena si lascia, torna a comandare il server.
     */
    var dragging by remember(client.id) { mutableStateOf(false) }
    var localVolume by remember(client.id) { mutableStateOf(client.volumePercent.toFloat()) }
    LaunchedEffect(client.volumePercent, dragging) {
        if (!dragging) localVolume = client.volumePercent.toFloat()
    }

    /*
     * Non si manda un comando a ogni pixel.
     *
     * Un trascinamento produce centinaia di valori al secondo: spedirli tutti
     * riempirebbe il canale di controllo di roba gia' vecchia, e il server li
     * rimanderebbe indietro a tutti i client collegati. Basta il valore intero,
     * e non piu' di uno ogni tanto: l'ultimo lo manda comunque il rilascio.
     */
    var lastSentPercent by remember(client.id) { mutableStateOf(client.volumePercent) }
    var lastSentAt by remember(client.id) { mutableStateOf(0L) }

    var menu by remember(client.id) { mutableStateOf(false) }
    var renaming by remember(client.id) { mutableStateOf(false) }
    var latency by remember(client.id) { mutableStateOf(false) }

    val dim = !client.connected
    val muted = client.muted || groupMuted

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                if (isSelf) accent.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = client.displayName(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = buildList {
                        if (isSelf) add(stringResource(R.string.snap_this_device))
                        if (client.ip.isNotBlank()) add(client.ip)
                        if (!client.connected) add(stringResource(R.string.snap_client_offline))
                        if (client.latencyMs != 0) {
                            add(stringResource(R.string.snap_latency_value, client.latencyMs))
                        }
                    }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelf) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = "${localVolume.toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (muted) MaterialTheme.colorScheme.error else accent
            )

            Spacer(Modifier.width(4.dp))

            FilledTonalIconButton(
                onClick = {
                    haptics.toggle(!client.muted)
                    onSetVolume(client.id, client.volumePercent, !client.muted)
                },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = if (client.muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                    contentDescription = stringResource(R.string.snap_mute),
                    modifier = Modifier.size(16.dp),
                    tint = if (client.muted) MaterialTheme.colorScheme.error else accent
                )
            }

            Box {
                IconButton(onClick = { haptics.tap(); menu = true }) {
                    Icon(
                        Icons.Outlined.Groups,
                        contentDescription = stringResource(R.string.snap_move_group),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.snap_rename)) },
                        leadingIcon = {
                            Icon(Icons.Outlined.DriveFileRenameOutline, null, Modifier.size(18.dp))
                        },
                        onClick = { menu = false; renaming = true }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.snap_latency)) },
                        leadingIcon = { Icon(Icons.Outlined.Timer, null, Modifier.size(18.dp)) },
                        onClick = { menu = false; latency = true }
                    )
                    if (otherGroups.isNotEmpty() || canSplit) HorizontalDivider()
                    otherGroups.forEach { g ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.snap_move_to) + " " + g.displayName()) },
                            leadingIcon = { Icon(Icons.Outlined.Workspaces, null, Modifier.size(18.dp)) },
                            onClick = { menu = false; onMoveClient(client.id, g.id) }
                        )
                    }
                    if (canSplit) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.snap_move_new_group)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.AddCircleOutline, null, Modifier.size(18.dp))
                            },
                            onClick = { menu = false; onSplitClient(client.id) }
                        )
                    }
                }
            }
        }

        Slider(
            value = localVolume,
            onValueChange = { v ->
                dragging = true
                localVolume = v
                val percent = v.toInt()
                val now = System.currentTimeMillis()
                if (percent != lastSentPercent && now - lastSentAt >= VOLUME_SEND_INTERVAL_MS) {
                    lastSentPercent = percent
                    lastSentAt = now
                    onSetVolume(client.id, percent, client.muted)
                }
            },
            onValueChangeFinished = {
                dragging = false
                haptics.tick()
                // Il valore finale si manda sempre: e' quello che l'utente ha
                // scelto, e potrebbe non essere passato dal filtro qui sopra.
                val percent = localVolume.toInt()
                lastSentPercent = percent
                lastSentAt = System.currentTimeMillis()
                onSetVolume(client.id, percent, client.muted)
            },
            valueRange = 0f..100f,
            enabled = client.connected,
            modifier = Modifier.fillMaxWidth()
        )

        if (renaming) {
            SnapTextPrompt(
                label = stringResource(R.string.snap_rename),
                initial = client.name,
                onDismiss = { renaming = false },
                onConfirm = { renaming = false; onSetName(client.id, it) }
            )
        }
        if (latency) {
            SnapLatencyPrompt(
                initial = client.latencyMs,
                accent = accent,
                onDismiss = { latency = false },
                onConfirm = { latency = false; onSetLatency(client.id, it) }
            )
        }
    }
}

/** Ogni quanto, al massimo, il trascinamento manda un comando al server. */
private const val VOLUME_SEND_INTERVAL_MS = 80L

/**
 * Un campo di testo che compare sul posto invece che in una finestra.
 *
 * Rinominare un client e' un'operazione da mezzo secondo: aprire un dialogo
 * modale per due parole interrompe il filo di chi sta sistemando i volumi.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnapTextPrompt(
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val haptics = rememberAppHaptics()
    var text by remember { mutableStateOf(initial) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.weight(1f)
        )
        FilledTonalIconButton(
            onClick = { haptics.confirm(); onConfirm(text.trim()) },
            enabled = text.isNotBlank(),
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
        }
        IconButton(onClick = { haptics.tap(); onDismiss() }) {
            Icon(Icons.Outlined.Close, null, Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SnapLatencyPrompt(
    initial: Int,
    accent: Color,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val haptics = rememberAppHaptics()
    var value by remember { mutableStateOf(initial.toFloat()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.snap_latency_value, value.toInt()),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                modifier = Modifier.weight(1f)
            )
            FilledTonalIconButton(
                onClick = { haptics.confirm(); onConfirm(value.toInt()) },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
            }
            IconButton(onClick = { haptics.tap(); onDismiss() }) {
                Icon(Icons.Outlined.Close, null, Modifier.size(18.dp))
            }
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = -500f..500f,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.snap_latency_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// I due pezzi che la schermata Ricevi inserisce
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Avvia la sessione e accende il servizio che la tiene viva.
 *
 * Le due cose vanno insieme sempre: collegarsi senza servizio vuol dire che
 * l'audio muore al primo blocco schermo, e accendere il servizio senza
 * collegarsi lascia una notifica che non racconta niente.
 */
internal fun startSnapcastSession(context: Context, ref: SnapcastServerRef) {
    SnapcastReceiver.connect(context, ref)
    ContextCompat.startForegroundService(
        context, Intent(context, SnapcastClientService::class.java)
    )
}

internal fun stopSnapcastSession(context: Context) {
    SnapcastReceiver.disconnect()
    context.stopService(Intent(context, SnapcastClientService::class.java))
}

/**
 * La parte che si vede prima di collegarsi: cosa c'e' in rete, e come
 * raggiungere quel che non si annuncia.
 *
 * Legge lo stato direttamente da [SnapcastReceiver] invece di farselo passare:
 * la sessione e' una sola per l'app e vive piu' a lungo della schermata, quindi
 * infilarla nei parametri di ExpressiveHomeScreen vorrebbe dire aggiungere una
 * dozzina di argomenti a una funzione che ne ha gia' troppi, e far passare per
 * la UI uno stato che non le appartiene.
 */
@Composable
fun SnapcastClientSection(appSettings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val discovered by SnapcastReceiver.discovered.collectAsState()
    val server by SnapcastReceiver.server.collectAsState()

    val saved = remember(appSettings.snapcastServers) {
        appSettings.snapcastServers.mapNotNull { SnapcastServerRef.deserialize(it) }
    }
    val connected = server != null

    // Si cerca solo mentre si guarda la lista: il lucchetto multicast costa
    // batteria, e cercare mentre si sta gia' ascoltando non serve a niente.
    DisposableEffect(connected, appSettings.networkInterface) {
        if (!connected) {
            SnapcastReceiver.startDiscovery(context, appSettings.networkInterface)
        } else {
            SnapcastReceiver.stopDiscovery()
        }
        onDispose { SnapcastReceiver.stopDiscovery() }
    }

    fun persist(list: List<SnapcastServerRef>) {
        scope.launch { SettingsDataStore(context).saveSnapcastServers(list.map { it.serialize() }) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        ExpressiveSnapcastDiscoverySection(
            servers = discovered,
            onConnect = { startSnapcastSession(context, it) }
        )
        ExpressiveSnapcastConnectCard(
            saved = saved,
            onConnect = { startSnapcastSession(context, it) },
            onSave = { ref -> persist(saved.filterNot { it.key == ref.key } + ref) },
            onForget = { ref -> persist(saved.filterNot { it.key == ref.key }) }
        )
    }
}

/**
 * La parte che si vede mentre si ascolta: cosa sta suonando e tutto l'impianto.
 *
 * Sta dove sta la sessione WFAS, sotto la stessa forma viva: ricevere e'
 * ricevere, e da qui in poi la differenza la fa solo il segno in cima.
 */
@Composable
fun SnapcastSessionSection() {
    val context = LocalContext.current

    val stream by SnapcastReceiver.streamStatus.collectAsState()
    val control by SnapcastReceiver.controlStatus.collectAsState()
    val splitSupported by SnapcastReceiver.splitSupported.collectAsState()
    val selfId = remember { SnapcastReceiver.localClientId(context) }

    ExpressiveSnapcastSessionPanel(
        stream = stream,
        control = control,
        selfClientId = selfId,
        splitSupported = splitSupported,
        onSetVolume = { id, percent, muted -> SnapcastReceiver.setClientVolume(id, percent, muted) },
        onSetName = { id, name -> SnapcastReceiver.setClientName(id, name) },
        onSetLatency = { id, ms -> SnapcastReceiver.setClientLatency(id, ms) },
        onGroupMute = { id, mute -> SnapcastReceiver.setGroupMute(id, mute) },
        onGroupName = { id, name -> SnapcastReceiver.setGroupName(id, name) },
        onMoveClient = { id, group -> SnapcastReceiver.moveClient(id, group) },
        onSplitClient = { id -> SnapcastReceiver.splitClient(context, id) }
    )
}

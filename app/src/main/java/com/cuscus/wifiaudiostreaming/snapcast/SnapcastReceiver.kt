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
 * SnapcastReceiver.kt
 *
 * Il regista del client Snapcast: quello che la UI e il servizio chiamano.
 *
 * Sotto ci sono tre cose che non si conoscono fra loro — la ricerca in rete, il
 * canale audio e quello di controllo — e qui stanno insieme perche' per chi
 * guarda l'app sono una cosa sola: un impianto a cui ci si collega. Espone
 * StateFlow e non callback perche' e' quello che Compose sa osservare senza
 * che nessuno debba ricordarsi di disiscriversi.
 *
 * Sta in un object come il resto della rete di questa app: la sessione e' una
 * sola per definizione — un telefono suona in una stanza sola — e legarla a
 * una schermata vorrebbe dire perderla girando il telefono.
 */

package com.cuscus.wifiaudiostreaming.snapcast

import android.content.Context
import com.cuscus.wifiaudiostreaming.NetAddr
import com.cuscus.wifiaudiostreaming.WfasMulticastLock
import com.cuscus.wifiaudiostreaming.NetworkManager
import com.cuscus.wifiaudiostreaming.openPcmPlaybackSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.NetworkInterface

object SnapcastReceiver {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var browser: SnapcastBrowser? = null
    private var stream: SnapcastStreamClient? = null
    private var control: SnapcastControlClient? = null
    private var browseScope: CoroutineScope? = null
    private var sessionScope: CoroutineScope? = null

    private val _discovered = MutableStateFlow<List<SnapcastServerRef>>(emptyList())
    val discovered: StateFlow<List<SnapcastServerRef>> = _discovered.asStateFlow()

    private val _streamStatus = MutableStateFlow(SnapStreamStatus())
    val streamStatus: StateFlow<SnapStreamStatus> = _streamStatus.asStateFlow()

    private val _controlStatus = MutableStateFlow(SnapControlStatus())
    val controlStatus: StateFlow<SnapControlStatus> = _controlStatus.asStateFlow()

    /** Il server a cui si e' collegati, se ce n'e' uno. */
    private val _server = MutableStateFlow<SnapcastServerRef?>(null)
    val server: StateFlow<SnapcastServerRef?> = _server.asStateFlow()

    /**
     * Alcuni server non rialloggiano un client staccato da un gruppo e lo
     * lasciano orfano. Lo si scopre solo provando: dopo un tentativo andato
     * male la voce non si propone piu' per gli altri dispositivi.
     */
    private val _splitSupported = MutableStateFlow(true)
    val splitSupported: StateFlow<Boolean> = _splitSupported.asStateFlow()

    val active: Boolean get() = stream != null || control != null

    /**
     * L'identita' di questo telefono verso il server.
     *
     * Il server ricorda volume, nome, latenza e gruppo per identificatore: se
     * cambiasse a ogni avvio, ogni riconnessione comparirebbe come un
     * dispositivo nuovo e le regolazioni dell'utente andrebbero perse.
     *
     * snapclient usa il MAC, e l'app desktop pure. Qui no: su Android il MAC
     * non e' leggibile dal 6 in poi, e quel che si potrebbe usare al suo posto
     * — ANDROID_ID — e' un identificatore del dispositivo che finirebbe a
     * girare sulla rete locale. Un numero casuale generato la prima volta e
     * tenuto qui fa lo stesso mestiere senza dire niente di chi lo usa.
     */
    fun localClientId(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences("wfas_snapcast_client", Context.MODE_PRIVATE)
        prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = "wfas-" + java.util.UUID.randomUUID().toString().take(17)
        prefs.edit().putString(KEY_CLIENT_ID, fresh).apply()
        return fresh
    }

    private const val KEY_CLIENT_ID = "client_id"

    fun localClientName(): String = runCatching {
        android.os.Build.MODEL?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: "Android"

    // ── Ricerca ─────────────────────────────────────────────────────────────

    fun startDiscovery(context: Context, preferredInterface: String) {
        if (browser != null) return
        WfasMulticastLock.acquire(context, "snapcast-mdns")
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        browseScope = s
        browser = SnapcastBrowser(
            scope = s,
            interfaceProvider = { activeInterface(context, preferredInterface) },
            // Il server Snapcast di questo stesso telefono non e' un posto a cui
            // collegarsi: sarebbe un anello chiuso, l'audio uscirebbe e
            // rientrerebbe da dove e' partito. Si annuncia in rete come tutti
            // gli altri, quindi va tolto qui.
            onServers = { list -> _discovered.value = list.filterNot { isOurselves(it.host) } }
        ).also { it.start() }
    }

    fun stopDiscovery() {
        browser?.stop()
        browser = null
        browseScope?.cancel()
        browseScope = null
        _discovered.value = emptyList()
        WfasMulticastLock.release("snapcast-mdns")
    }

    private fun isOurselves(host: String): Boolean = runCatching {
        val mine = NetAddr.localAddresses().map { it.address.hostAddress }.toSet()
        host in mine || host == "127.0.0.1" || host == "::1" || host == "localhost"
    }.getOrDefault(false)

    private fun activeInterface(context: Context, preferred: String): NetworkInterface? = runCatching {
        val all = NetworkInterface.getNetworkInterfaces().toList()
        if (preferred.isNotBlank() && !preferred.equals("Auto", true)) {
            all.firstOrNull { it.name == preferred || it.displayName == preferred }
        } else {
            val ip = NetworkManager.getLocalIpAddress(context)
            all.firstOrNull { iface ->
                iface.isUp && !iface.isLoopback &&
                        iface.inetAddresses.toList().any { it.hostAddress == ip }
            } ?: all.firstOrNull { it.isUp && !it.isLoopback && it.supportsMulticast() }
        }
    }.getOrNull()

    // ── Sessione ────────────────────────────────────────────────────────────

    /**
     * Nessun parametro di latenza, ed e' voluto: in Snapcast il buffer di
     * riproduzione lo decide il server, uguale per tutte le stanze, perche' e'
     * quello che le tiene in sincrono. Il ritardo di questo dispositivo si
     * regola dal canale di controllo, con setClientLatency, ed e' un'altra cosa.
     */
    fun connect(context: Context, ref: SnapcastServerRef) {
        disconnect()
        val app = context.applicationContext
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        sessionScope = s
        _server.value = ref
        _splitSupported.value = true

        startStream(ref, localClientId(app), s)

        control = SnapcastControlClient(
            host = ref.host,
            port = ref.controlPort,
            onStatus = { _controlStatus.value = it }
        ).also { it.start(s) }
    }

    private fun startStream(ref: SnapcastServerRef, id: String, s: CoroutineScope) {
        stream?.stop()
        stream = SnapcastStreamClient(
            server = ref,
            clientId = id,
            clientName = localClientName(),
            onStatus = { _streamStatus.value = it },
            onPcm = null,
            openPlayer = { rate, ch, bufferMs ->
                openPcmPlaybackSink(rate, ch, bufferMs, "SNAPCAST")
            }
        ).also { it.start(s) }
    }

    fun disconnect() {
        stream?.stop(); stream = null
        control?.stop(); control = null
        sessionScope?.cancel(); sessionScope = null
        _streamStatus.value = SnapStreamStatus()
        _controlStatus.value = SnapControlStatus()
        _server.value = null
        _splitSupported.value = true
    }

    // ── Comandi ─────────────────────────────────────────────────────────────

    fun setClientVolume(clientId: String, percent: Int, muted: Boolean) {
        control?.setClientVolume(clientId, percent, muted)
    }

    fun setClientName(clientId: String, name: String) { control?.setClientName(clientId, name) }

    fun setClientLatency(clientId: String, ms: Int) { control?.setClientLatency(clientId, ms) }

    fun setGroupMute(groupId: String, mute: Boolean) { control?.setGroupMute(groupId, mute) }

    fun setGroupName(groupId: String, name: String) { control?.setGroupName(groupId, name) }

    fun setGroupStream(groupId: String, streamId: String) { control?.setGroupStream(groupId, streamId) }

    fun refresh() { control?.refresh() }

    /**
     * Sposta un client in un gruppo esistente.
     *
     * In Snapcast non c'e' uno "sposta": l'unico comando riscrive l'intera
     * composizione del gruppo di destinazione, quindi si prendono i suoi client
     * e si aggiunge quello spostato. Al vecchio gruppo ci pensa il server.
     */
    fun moveClient(clientId: String, targetGroupId: String) {
        val target = _controlStatus.value.status.groups.firstOrNull { it.id == targetGroupId } ?: return
        control?.setGroupClients(targetGroupId, (target.clients.map { it.id } + clientId).distinct())
        control?.refresh()
    }

    /**
     * Stacca un client nel suo gruppo, da solo.
     *
     * Per noi stessi funziona sempre: usciti dal gruppo ci si ricollega, e il
     * server un gruppo nuovo a chi si presenta senza lo da'. Per gli altri no —
     * la riconnessione altrui non la comanda nessuno — quindi si verifica, e se
     * il server l'ha lasciato orfano lo si rimette dov'era e non si propone
     * piu'.
     */
    fun splitClient(context: Context, clientId: String) {
        val ctrl = control ?: return
        val st = _controlStatus.value.status
        val group = st.groups.firstOrNull { g -> g.clients.any { it.id == clientId } } ?: return
        if (group.clients.size <= 1) return

        val original = group.clients.map { it.id }
        ctrl.setGroupClients(group.id, original.filter { it != clientId })
        ctrl.refresh()

        val app = context.applicationContext
        val isSelf = clientId == localClientId(app)
        scope.launch {
            if (isSelf) {
                // Mezzo secondo di silenzio, e ci si ripresenta senza gruppo.
                delay(250)
                val ref = _server.value
                val s = sessionScope
                if (ref != null && s != null) {
                    stream?.stop()
                    stream = null
                    _streamStatus.value = _streamStatus.value.copy(state = SnapStreamState.CONNECTING)
                    delay(500)
                    startStream(ref, localClientId(app), s)
                }
                delay(1200)
                ctrl.refresh()
            } else {
                delay(1500)
                val stillListed = _controlStatus.value.status.groups
                    .any { g -> g.clients.any { it.id == clientId } }
                if (!stillListed) {
                    SnapLog.d("[Snapcast/ctrl] il server non ha rialloggiato $clientId: ripristino")
                    _splitSupported.value = false
                    ctrl.setGroupClients(group.id, original)
                    ctrl.refresh()
                }
            }
        }
    }
}

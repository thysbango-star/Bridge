package com.flowshare.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flowshare.app.core.DeviceIdentity
import com.flowshare.app.core.FileStore
import com.flowshare.app.core.LocalFile
import com.flowshare.app.nearby.Peer
import com.flowshare.app.nearby.PeerStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Screen { Home, Nearby, ShowQr }

class FlowViewModel(app: Application) : AndroidViewModel(app) {
    private val flowApp = app as FlowShareApp
    val nearby = flowApp.nearby
    val identity = flowApp.identity

    private val _screen = MutableStateFlow(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _selected = MutableStateFlow<List<LocalFile>>(emptyList())
    val selected: StateFlow<List<LocalFile>> = _selected.asStateFlow()

    private val _deviceName = MutableStateFlow(identity.displayName)
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    /** Token from a scanned QR code; we auto-connect as soon as that phone is discovered. */
    private val _pendingQr = MutableStateFlow<Pair<String, String>?>(null)
    val pendingQr: StateFlow<Pair<String, String>?> = _pendingQr.asStateFlow()

    val peers: StateFlow<List<Peer>> = nearby.peers
        .map { m -> m.values.sortedBy { it.foundAt } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val prefs = FlowPrefs(app)
    private val _receiveAnywhere = MutableStateFlow(prefs.receiveAnywhere)
    val receiveAnywhere: StateFlow<Boolean> = _receiveAnywhere.asStateFlow()
    private val _overlayAllowed = MutableStateFlow(FlowPrefs.canDrawOverlays(app))
    val overlayAllowed: StateFlow<Boolean> = _overlayAllowed.asStateFlow()

    val session = nearby.session
    val offer = nearby.offer
    val radio = nearby.radio
    val events = nearby.events

    init {
        viewModelScope.launch {
            nearby.peers.collect { m ->
                val qr = _pendingQr.value ?: return@collect
                val match = m.values.firstOrNull { it.token == qr.second }
                if (match != null && match.status == PeerStatus.AVAILABLE && _selected.value.isNotEmpty()) {
                    _pendingQr.value = null
                    nearby.send(match.endpointId, _selected.value, qr.second)
                }
            }
        }
    }

    fun startRadios() {
        nearby.start()
        if (prefs.receiveAnywhere) com.flowshare.app.nearby.ReceiveService.start(getApplication())
    }

    /** Re-read settings that can change outside the app (overlay permission, notification "Turn off"). */
    fun refreshSettings() {
        _receiveAnywhere.value = prefs.receiveAnywhere
        _overlayAllowed.value = FlowPrefs.canDrawOverlays(getApplication())
    }

    fun setReceiveAnywhere(on: Boolean) {
        prefs.receiveAnywhere = on
        _receiveAnywhere.value = on
        if (on) com.flowshare.app.nearby.ReceiveService.start(getApplication())
        else com.flowshare.app.nearby.ReceiveService.stop(getApplication())
    }

    fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val files = FileStore.describe(getApplication(), uris)
            if (files.isNotEmpty()) {
                _selected.value = files
                _screen.value = Screen.Nearby
                nearby.rescan()
            }
        }
    }

    fun sendTo(peer: Peer) {
        if (_selected.value.isEmpty() || peer.status != PeerStatus.AVAILABLE) return
        nearby.send(peer.endpointId, _selected.value, null)
    }

    /** Bump-to-share: if exactly one phone is nearby, a bump connects to it. */
    fun onBump(): Boolean {
        val available = peers.value.filter { it.status == PeerStatus.AVAILABLE }
        if (available.size == 1 && _selected.value.isNotEmpty() && !nearby.busy()) {
            sendTo(available.first())
            return true
        }
        return false
    }

    fun onQrScanned(text: String): Boolean {
        val parsed = DeviceIdentity.parseQr(text) ?: return false
        _pendingQr.value = parsed
        // If already discovered, the collector above connects immediately.
        val match = peers.value.firstOrNull { it.token == parsed.second }
        if (match != null && match.status == PeerStatus.AVAILABLE && _selected.value.isNotEmpty()) {
            _pendingQr.value = null
            nearby.send(match.endpointId, _selected.value, parsed.second)
        } else {
            nearby.rescan()
        }
        return true
    }

    fun cancelQr() { _pendingQr.value = null }

    fun go(screen: Screen) {
        _screen.value = screen
        if (screen == Screen.Home) {
            _pendingQr.value = null
        }
    }

    fun clearSelection() {
        _selected.value = emptyList()
        _screen.value = Screen.Home
    }

    fun rename(name: String) {
        identity.rename(name)
        _deviceName.value = identity.displayName
        nearby.restartAdvertising()
    }

    fun setReceiving(enabled: Boolean) = nearby.setReceiving(enabled)
    fun accept() = nearby.acceptOffer()
    fun decline() = nearby.declineOffer()
    fun cancelTransfer() = nearby.cancel()

    fun finishSession() {
        val wasSend = session.value?.direction == com.flowshare.app.nearby.Direction.SEND
        val ok = session.value?.state == com.flowshare.app.nearby.SessionState.COMPLETE
        nearby.clearSession()
        if (wasSend && ok) clearSelection()
    }
}

package com.flowshare.app.nearby

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.flowshare.app.core.DeviceIdentity
import com.flowshare.app.core.FileMeta
import com.flowshare.app.core.FileStore
import com.flowshare.app.core.LocalFile
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the Nearby Connections client: advertising + discovery, the encrypted connection,
 * the offer/accept handshake and the file payload transfers.
 *
 * Security: every Nearby connection is authenticated and encrypted with UKEY2 key exchange
 * (AES-256-GCM). Both phones show the same 4-digit code from that handshake so the user can
 * verify there is no man-in-the-middle, and nothing is written to storage until the receiver
 * taps Accept.
 */
class NearbyManager(private val context: Context, private val identity: DeviceIdentity) {

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private val _offer = MutableStateFlow<IncomingOffer?>(null)
    val offer: StateFlow<IncomingOffer?> = _offer.asStateFlow()

    private val _session = MutableStateFlow<TransferSession?>(null)
    val session: StateFlow<TransferSession?> = _session.asStateFlow()

    private val _radio = MutableStateFlow(RadioState())
    val radio: StateFlow<RadioState> = _radio.asStateFlow()

    private val _events = MutableSharedFlow<FlowEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<FlowEvent> = _events

    data class RadioState(
        val advertising: Boolean = false,
        val discovering: Boolean = false,
        val receivingEnabled: Boolean = true,
        val error: String? = null,
    )

    // --- connection bookkeeping ---
    private val outgoingPlans = HashMap<String, List<LocalFile>>()
    private val outgoingQr = HashMap<String, String?>()
    private val authCodes = HashMap<String, String>()
    private val connected = HashSet<String>()
    private val sendPayloads = LinkedHashMap<Long, Payload>()
    private val openFds = ArrayList<ParcelFileDescriptor>()
    private val receivedFiles = HashMap<Long, Payload>()
    private var offerTimeout: Job? = null

    // --- speed estimation ---
    private var speedT = 0L
    private var speedBytes = 0L
    private var speedEma = 0.0

    // ---------------------------------------------------------------- radios

    fun start() {
        startAdvertising()
        startDiscovery()
    }

    fun setReceiving(enabled: Boolean) {
        _radio.update { it.copy(receivingEnabled = enabled) }
        if (enabled) startAdvertising() else {
            client.stopAdvertising()
            _radio.update { it.copy(advertising = false) }
        }
    }

    fun restartAdvertising() {
        client.stopAdvertising()
        _radio.update { it.copy(advertising = false) }
        startAdvertising()
    }

    fun stopRadios() {
        client.stopAdvertising()
        client.stopDiscovery()
        _radio.update { it.copy(advertising = false, discovering = false) }
    }

    private fun startAdvertising() {
        val r = _radio.value
        if (r.advertising || !r.receivingEnabled) return
        _radio.update { it.copy(advertising = true, error = null) }
        client.startAdvertising(
            identity.advertisedName, SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { e ->
            if (code(e) != ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) {
                Log.w(TAG, "advertising failed", e)
                _radio.update { it.copy(advertising = false, error = describe(e)) }
            }
        }
    }

    private fun startDiscovery() {
        if (_radio.value.discovering) return
        _radio.update { it.copy(discovering = true, error = null) }
        client.startDiscovery(
            SERVICE_ID, discovery,
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { e ->
            if (code(e) != ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
                Log.w(TAG, "discovery failed", e)
                _radio.update { it.copy(discovering = false, error = describe(e)) }
            }
        }
    }

    /** Restart discovery to refresh the device list. */
    fun rescan() {
        if (busy()) return
        client.stopDiscovery()
        _radio.update { it.copy(discovering = false) }
        _peers.update { m -> m.filterKeys { it in connected } }
        startDiscovery()
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            val (name, token) = DeviceIdentity.parseAdvertised(info.endpointName)
            if (token == identity.pairingToken) return
            _peers.update { m ->
                val prev = m[endpointId]
                m + (endpointId to (prev?.copy(name = name, token = token) ?: Peer(endpointId, name, token)))
            }
        }

        override fun onEndpointLost(endpointId: String) {
            if (endpointId in connected || outgoingPlans.containsKey(endpointId)) return
            _peers.update { it - endpointId }
        }
    }

    // ---------------------------------------------------------------- sending

    fun busy(): Boolean = _session.value?.state?.isLive == true || _offer.value != null

    /** Connects to [endpointId] and offers [files]. The receiver must accept before anything is sent. */
    fun send(endpointId: String, files: List<LocalFile>, qrToken: String? = null) {
        if (files.isEmpty() || busy()) return
        val peer = _peers.value[endpointId]
        outgoingPlans[endpointId] = files
        outgoingQr[endpointId] = qrToken
        setPeerStatus(endpointId, PeerStatus.CONNECTING)
        _session.value = TransferSession(
            endpointId = endpointId,
            peerName = peer?.name ?: "Nearby phone",
            direction = Direction.SEND,
            state = SessionState.CONNECTING,
            files = files.map { FileTransfer(FileMeta(0, it.name, it.size, it.mime)) },
            verifiedByQr = qrToken != null,
        )
        client.requestConnection(identity.advertisedName, endpointId, lifecycle)
            .addOnFailureListener { e ->
                if (code(e) == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) return@addOnFailureListener
                Log.w(TAG, "requestConnection failed", e)
                endSession(SessionState.FAILED, "Couldn't connect to ${peer?.name ?: "phone"}. ${describe(e)}")
                cleanupEndpoint(endpointId)
            }
    }

    private fun sendOffer(endpointId: String, files: List<LocalFile>) {
        val metas = ArrayList<FileMeta>()
        closeFds()
        sendPayloads.clear()
        for (f in files) {
            val pfd = runCatching { context.contentResolver.openFileDescriptor(f.uri, "r") }.getOrNull()
            if (pfd == null) {
                Log.w(TAG, "cannot open ${f.uri}")
                continue
            }
            openFds += pfd
            val payload = Payload.fromFile(pfd)
            sendPayloads[payload.id] = payload
            val size = if (f.size >= 0) f.size else pfd.statSize
            metas += FileMeta(payload.id, f.name, size, f.mime)
        }
        if (metas.isEmpty()) {
            endSession(SessionState.FAILED, "The selected files couldn't be read.")
            client.disconnectFromEndpoint(endpointId)
            cleanupEndpoint(endpointId)
            return
        }
        val qr = outgoingQr[endpointId]
        client.sendPayload(endpointId, Payload.fromBytes(Message.Offer(identity.displayName, metas, qr).encode()))
        _session.update {
            it?.copy(
                state = SessionState.WAITING_ACCEPT,
                files = metas.map { m -> FileTransfer(m) },
                authCode = authCodes[endpointId].orEmpty(),
            )
        }
    }

    private fun startSending(endpointId: String) {
        resetSpeed()
        _session.update { it?.copy(state = SessionState.TRANSFERRING, startedAt = System.currentTimeMillis()) }
        TransferService.start(context)
        // Nearby queues payloads and sends them in order.
        sendPayloads.values.forEach { client.sendPayload(endpointId, it) }
    }

    // ---------------------------------------------------------------- receiving

    fun acceptOffer() {
        val o = _offer.value ?: return
        _offer.value = null
        offerTimeout?.cancel()
        resetSpeed()
        _session.value = TransferSession(
            endpointId = o.endpointId,
            peerName = o.senderName,
            direction = Direction.RECEIVE,
            state = SessionState.TRANSFERRING,
            files = o.files.map { FileTransfer(it) },
            authCode = o.authCode,
            verifiedByQr = o.verifiedByQr,
        )
        client.sendPayload(o.endpointId, Payload.fromBytes(Message.Accept.encode()))
        TransferService.start(context)
    }

    fun declineOffer() {
        val o = _offer.value ?: return
        _offer.value = null
        offerTimeout?.cancel()
        client.sendPayload(o.endpointId, Payload.fromBytes(Message.Decline.encode()))
        scope.launch {
            delay(400)
            client.disconnectFromEndpoint(o.endpointId)
            cleanupEndpoint(o.endpointId)
        }
    }

    // ---------------------------------------------------------------- cancel / end

    fun cancel() {
        val s = _session.value ?: return
        if (s.state.isLive) {
            if (s.endpointId in connected) {
                client.sendPayload(s.endpointId, Payload.fromBytes(Message.Cancel.encode()))
            }
            s.files.forEach { if (it.status != FileStatus.DONE && it.meta.id != 0L) client.cancelPayload(it.meta.id) }
            endSession(SessionState.CANCELLED, "Transfer cancelled")
            scope.launch {
                delay(300)
                client.disconnectFromEndpoint(s.endpointId)
                cleanupEndpoint(s.endpointId)
            }
        }
    }

    /** Dismisses a finished session and returns to discovery. */
    fun clearSession() {
        val s = _session.value
        if (s != null && s.state.isLive) cancel()
        _session.value = null
        startDiscovery()
        startAdvertising()
    }

    private fun endSession(state: SessionState, message: String? = null) {
        _session.update { it?.copy(state = state, message = message, bytesPerSecond = 0.0) }
        TransferService.stop(context)
    }

    private fun cleanupEndpoint(endpointId: String) {
        outgoingPlans.remove(endpointId)
        outgoingQr.remove(endpointId)
        authCodes.remove(endpointId)
        connected.remove(endpointId)
        receivedFiles.clear()
        sendPayloads.clear()
        closeFds()
        setPeerStatus(endpointId, PeerStatus.AVAILABLE)
        if (_offer.value?.endpointId == endpointId) _offer.value = null
        startDiscovery()
        startAdvertising()
    }

    private fun closeFds() {
        openFds.forEach { runCatching { it.close() } }
        openFds.clear()
    }

    // ---------------------------------------------------------------- callbacks

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val outgoing = outgoingPlans.containsKey(endpointId)
            if (!outgoing && (busy() || !_radio.value.receivingEnabled)) {
                client.rejectConnection(endpointId)
                return
            }
            authCodes[endpointId] = info.authenticationDigits
            if (!outgoing) {
                val (name, token) = DeviceIdentity.parseAdvertised(info.endpointName)
                _peers.update { m ->
                    m + (endpointId to (m[endpointId]?.copy(status = PeerStatus.CONNECTING)
                        ?: Peer(endpointId, name, token, PeerStatus.CONNECTING)))
                }
            }
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val outgoing = outgoingPlans[endpointId]
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                if (outgoing != null) {
                    val msg = if (result.status.statusCode == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED)
                        "The other phone is busy or not receiving." else "Connection failed. Try again."
                    endSession(SessionState.FAILED, msg)
                }
                cleanupEndpoint(endpointId)
                return
            }
            connected += endpointId
            setPeerStatus(endpointId, PeerStatus.CONNECTED)
            // Free the radio so Nearby can upgrade to Wi-Fi for maximum bandwidth.
            client.stopDiscovery()
            _radio.update { it.copy(discovering = false) }
            _events.tryEmit(FlowEvent.Contact(endpointId, incoming = outgoing == null))

            if (outgoing != null) {
                sendOffer(endpointId, outgoing)
            } else {
                offerTimeout?.cancel()
                offerTimeout = scope.launch {
                    delay(OFFER_TIMEOUT_MS)
                    if (_offer.value?.endpointId != endpointId && _session.value?.endpointId != endpointId) {
                        client.disconnectFromEndpoint(endpointId)
                        cleanupEndpoint(endpointId)
                    }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            val s = _session.value
            if (s != null && s.endpointId == endpointId && s.state.isLive) {
                val msg = if (s.direction == Direction.SEND && s.state == SessionState.WAITING_ACCEPT)
                    "${s.peerName} didn't accept." else "Connection to ${s.peerName} was lost."
                endSession(SessionState.FAILED, msg)
            }
            cleanupEndpoint(endpointId)
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> payload.asBytes()?.let { handleMessage(endpointId, it) }
                Payload.Type.FILE -> {
                    val s = _session.value
                    // Only accept files we agreed to receive.
                    if (s == null || s.direction != Direction.RECEIVE || s.files.none { it.meta.id == payload.id }) {
                        client.cancelPayload(payload.id)
                        return
                    }
                    receivedFiles[payload.id] = payload
                }
                else -> client.cancelPayload(payload.id)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val s = _session.value ?: return
            if (s.endpointId != endpointId) return
            val index = s.files.indexOfFirst { it.meta.id == update.payloadId }
            if (index < 0) return
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> updateFile(index) {
                    it.copy(transferred = update.bytesTransferred, status = FileStatus.ACTIVE)
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    val size = if (update.totalBytes > 0) update.totalBytes else s.files[index].meta.size
                    if (s.direction == Direction.SEND) {
                        updateFile(index) { it.copy(transferred = size, status = FileStatus.DONE) }
                        checkComplete()
                    } else {
                        updateFile(index) { it.copy(transferred = size) }
                        finishReceivedFile(index, update.payloadId)
                    }
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    updateFile(index) { it.copy(status = FileStatus.FAILED) }
                    if (_session.value?.state == SessionState.TRANSFERRING) {
                        endSession(SessionState.FAILED, "“${s.files[index].meta.name}” failed to transfer.")
                        client.disconnectFromEndpoint(endpointId)
                        cleanupEndpoint(endpointId)
                    }
                }
            }
            sampleSpeed()
        }
    }

    private fun handleMessage(endpointId: String, bytes: ByteArray) {
        when (val m = Message.decode(bytes)) {
            is Message.Offer -> {
                if (outgoingPlans.containsKey(endpointId) || _session.value?.state?.isLive == true) return
                offerTimeout?.cancel()
                _offer.value = IncomingOffer(
                    endpointId = endpointId,
                    senderName = m.senderName,
                    authCode = authCodes[endpointId].orEmpty(),
                    files = m.files,
                    verifiedByQr = m.qrToken != null && m.qrToken == identity.pairingToken,
                )
            }
            Message.Accept -> if (_session.value?.state == SessionState.WAITING_ACCEPT) startSending(endpointId)
            Message.Decline -> {
                endSession(SessionState.DECLINED, "${_session.value?.peerName ?: "They"} declined.")
                scope.launch {
                    delay(300)
                    client.disconnectFromEndpoint(endpointId)
                    cleanupEndpoint(endpointId)
                }
            }
            Message.Cancel -> {
                if (_offer.value?.endpointId == endpointId) _offer.value = null
                endSession(SessionState.CANCELLED, "The other phone cancelled.")
                client.disconnectFromEndpoint(endpointId)
                cleanupEndpoint(endpointId)
            }
            null -> Log.w(TAG, "ignored malformed message")
        }
    }

    private fun finishReceivedFile(index: Int, payloadId: Long) {
        val payload = receivedFiles.remove(payloadId)
        val file = payload?.asFile()
        val meta = _session.value?.files?.getOrNull(index)?.meta ?: return
        if (file == null) {
            updateFile(index) { it.copy(status = FileStatus.FAILED) }
            checkComplete()
            return
        }
        val source: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            file.asUri()
        } else {
            @Suppress("DEPRECATION")
            file.asJavaFile()?.let { Uri.fromFile(it) }
        }
        scope.launch {
            val saved = source?.let { FileStore.saveIncoming(context, it, meta) }
            updateFile(index) {
                it.copy(status = if (saved != null) FileStatus.DONE else FileStatus.FAILED, savedUri = saved)
            }
            checkComplete()
        }
    }

    private fun checkComplete() {
        val s = _session.value ?: return
        if (s.state != SessionState.TRANSFERRING) return
        if (s.files.any { it.status == FileStatus.QUEUED || it.status == FileStatus.ACTIVE }) return
        val failed = s.files.count { it.status == FileStatus.FAILED }
        if (failed == 0) endSession(SessionState.COMPLETE)
        else endSession(SessionState.FAILED, "$failed of ${s.files.size} files couldn't be saved.")
        scope.launch {
            delay(1200)
            client.disconnectFromEndpoint(s.endpointId)
            cleanupEndpoint(s.endpointId)
        }
    }

    private inline fun updateFile(index: Int, crossinline f: (FileTransfer) -> FileTransfer) {
        _session.update { s ->
            if (s == null || index !in s.files.indices) s
            else s.copy(files = s.files.toMutableList().also { it[index] = f(it[index]) })
        }
    }

    private fun resetSpeed() {
        speedT = SystemClock.elapsedRealtime()
        speedBytes = 0L
        speedEma = 0.0
    }

    private fun sampleSpeed() {
        val s = _session.value ?: return
        val now = SystemClock.elapsedRealtime()
        val dt = now - speedT
        if (dt < 250) return
        val bytes = s.transferredBytes
        val inst = (bytes - speedBytes).coerceAtLeast(0) * 1000.0 / dt
        speedEma = if (speedEma <= 0) inst else speedEma * 0.7 + inst * 0.3
        speedT = now
        speedBytes = bytes
        _session.update { it?.copy(bytesPerSecond = speedEma) }
    }

    private fun setPeerStatus(endpointId: String, status: PeerStatus) {
        _peers.update { m -> m[endpointId]?.let { m + (endpointId to it.copy(status = status)) } ?: m }
    }

    private fun code(e: Exception): Int = (e as? ApiException)?.statusCode ?: -1

    private fun describe(e: Exception): String = when {
        e.message?.contains("MISSING_PERMISSION") == true -> "Nearby permissions are missing."
        code(e) == ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> "Bluetooth error — toggle Bluetooth and try again."
        else -> "Turn on Bluetooth, Wi-Fi and Location, then try again."
    }

    companion object {
        private const val TAG = "FlowShare"
        const val SERVICE_ID = "com.flowshare.app.FLOW"
        /** Point-to-point gives the highest bandwidth (Wi-Fi Direct / hotspot upgrade) for big files. */
        val STRATEGY: Strategy = Strategy.P2P_POINT_TO_POINT
        private const val OFFER_TIMEOUT_MS = 15_000L
    }
}

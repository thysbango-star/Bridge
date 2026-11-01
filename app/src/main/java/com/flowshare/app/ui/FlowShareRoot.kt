package com.flowshare.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.flowshare.app.FlowPrefs
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowshare.app.FlowViewModel
import com.flowshare.app.Screen
import com.flowshare.app.core.FileStore
import com.flowshare.app.core.FlowPermissions
import com.flowshare.app.core.Haptics
import com.flowshare.app.nearby.FlowEvent
import com.flowshare.app.nearby.IncomingOffer
import com.flowshare.app.nearby.SessionState
import com.flowshare.app.nearby.TransferSession
import com.flowshare.app.ui.liquid.liquidSurface
import com.flowshare.app.ui.liquid.rememberFlowTime
import com.flowshare.app.ui.liquid.rememberLiquidController
import com.flowshare.app.ui.screens.HomeScreen
import com.flowshare.app.ui.screens.IncomingCard
import com.flowshare.app.ui.screens.NearbyScreen
import com.flowshare.app.ui.screens.PermissionScreen
import com.flowshare.app.ui.screens.ShowQrScreen
import com.flowshare.app.ui.screens.TransferScreen
import com.flowshare.app.ui.theme.Flow
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

private enum class Dest { Home, Nearby, ShowQr, Transfer }

@Composable
fun FlowShareRoot(vm: FlowViewModel) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(FlowPermissions.allGranted(context)) }

    val time = rememberFlowTime()
    val liquid = rememberLiquidController(time)

    val screen by vm.screen.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val offer by vm.offer.collectAsStateWithLifecycle()
    val peers by vm.peers.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val radio by vm.radio.collectAsStateWithLifecycle()
    val deviceName by vm.deviceName.collectAsStateWithLifecycle()
    val pendingQr by vm.pendingQr.collectAsStateWithLifecycle()
    val receiveAnywhere by vm.receiveAnywhere.collectAsStateWithLifecycle()
    val overlayAllowed by vm.overlayAllowed.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshSettings() }
    val overlaySettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshSettings()
    }
    val toggleAnywhere: (Boolean) -> Unit = { on ->
        vm.setReceiveAnywhere(on)
        if (on && !FlowPrefs.canDrawOverlays(context)) {
            Toast.makeText(context, "Allow Flow Share to display over other apps for the animation", Toast.LENGTH_LONG).show()
            runCatching {
                overlaySettings.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                )
            }
        }
    }

    LaunchedEffect(granted) { if (granted) vm.startRadios() }

    // Contact bloom on both phones as soon as the encrypted link is up.
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is FlowEvent.Contact -> {
                    Haptics.contact(context)
                    liquid.contact()
                }
                is FlowEvent.Notice -> Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Transfer start → flowing pulse; completion → settling wave + success haptic.
    val state = session?.state
    LaunchedEffect(state) {
        when (state) {
            SessionState.TRANSFERRING -> liquid.pulse()
            SessionState.COMPLETE -> {
                Haptics.success(context)
                liquid.settle()
            }
            SessionState.DECLINED, SessionState.FAILED -> Haptics.reject(context)
            else -> Unit
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.onFilesPicked(uris)
    }
    val pick = { picker.launch(arrayOf("*/*")) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        if (!vm.onQrScanned(text)) Toast.makeText(context, "That isn't a Flow Share code", Toast.LENGTH_SHORT).show()
    }
    val scan = {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the Flow Share code on the other phone")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    // Keep the last non-null values so exit animations have something to draw.
    var lastSession by remember { mutableStateOf<TransferSession?>(null) }
    session?.let { lastSession = it }
    var lastOffer by remember { mutableStateOf<IncomingOffer?>(null) }
    offer?.let { lastOffer = it }

    val dest = when {
        session != null && session?.state != SessionState.CONNECTING -> Dest.Transfer
        screen == Screen.Nearby && selected.isNotEmpty() -> Dest.Nearby
        screen == Screen.ShowQr -> Dest.ShowQr
        else -> Dest.Home
    }

    BackHandler(enabled = dest != Dest.Home || offer != null) {
        when {
            offer != null -> vm.decline()
            dest == Dest.Transfer -> if (session?.state?.isLive == true) vm.cancelTransfer() else vm.finishSession()
            dest == Dest.Nearby -> vm.clearSelection()
            else -> vm.go(Screen.Home)
        }
    }

    // Everything inside this Box is rendered into one layer, so the liquid shader bends the real UI.
    Box(
        Modifier
            .fillMaxSize()
            .liquidSurface(liquid)
            .background(Flow.Black),
    ) {
        if (!granted) {
            PermissionScreen(time) { granted = true }
            return@Box
        }

        AnimatedContent(
            targetState = dest,
            transitionSpec = {
                if (targetState == Dest.Transfer || initialState == Dest.Transfer) {
                    // Swap screens underneath the bloom, at its brightest moment (~0.45s in).
                    (fadeIn(tween(520, delayMillis = 420)) +
                        scaleIn(tween(700, delayMillis = 420, easing = FastOutSlowInEasing), 0.94f, TransformOrigin(0.5f, 0f)))
                        .togetherWith(fadeOut(tween(260, delayMillis = 300)))
                } else {
                    (fadeIn(tween(380)) + slideInVertically(tween(450)) { it / 14 })
                        .togetherWith(fadeOut(tween(200)))
                }
            },
            label = "dest",
        ) { d ->
            when (d) {
                Dest.Home -> HomeScreen(
                    time = time,
                    deviceName = deviceName,
                    receiving = radio.receivingEnabled,
                    radioError = radio.error,
                    onShare = pick,
                    onShowQr = { vm.go(Screen.ShowQr) },
                    onToggleReceiving = vm::setReceiving,
                    onRename = vm::rename,
                    receiveAnywhere = receiveAnywhere,
                    overlayAllowed = overlayAllowed,
                    onToggleAnywhere = toggleAnywhere,
                )
                Dest.Nearby -> NearbyScreen(
                    time = time,
                    files = selected,
                    peers = peers,
                    pendingQrName = pendingQr?.first,
                    radioError = radio.error,
                    onBack = vm::clearSelection,
                    onChangeFiles = pick,
                    onPeer = { vm.sendTo(it) },
                    onBump = {
                        val ok = vm.onBump()
                        if (ok) Haptics.tick(context)
                        ok
                    },
                    onScanQr = scan,
                    onCancelQr = vm::cancelQr,
                    onRescan = { vm.nearby.rescan() },
                )
                Dest.ShowQr -> ShowQrScreen(time, vm.identity.qrPayload, deviceName) { vm.go(Screen.Home) }
                Dest.Transfer -> lastSession?.let { s ->
                    TransferScreen(
                        s = s,
                        time = time,
                        onCancel = vm::cancelTransfer,
                        onDone = vm::finishSession,
                        onOpenFile = {
                            s.files.firstOrNull()?.let { f -> f.savedUri?.let { FileStore.open(context, it, f.meta.mime) } }
                        },
                        onShowFiles = { FileStore.openDownloads(context) },
                    )
                }
            }
        }

        // Incoming request: scrim + card that rises out of the contact (top) edge.
        AnimatedVisibility(
            visible = offer != null,
            enter = fadeIn(tween(400, delayMillis = 300)),
            exit = fadeOut(tween(250)),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
            )
        }
        AnimatedVisibility(
            visible = offer != null,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(350, delayMillis = 450)) +
                slideInVertically(spring(dampingRatio = 0.72f, stiffness = 260f)) { -it } +
                scaleIn(tween(600, delayMillis = 450), 0.9f, TransformOrigin(0.5f, 0f)),
            exit = slideOutVertically(tween(300)) { -it } + fadeOut(tween(250)),
        ) {
            lastOffer?.let { o ->
                IncomingCard(
                    offer = o,
                    onAccept = vm::accept,
                    onDecline = vm::decline,
                    modifier = Modifier.systemBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }
}

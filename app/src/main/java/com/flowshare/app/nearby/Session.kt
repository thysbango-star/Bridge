package com.flowshare.app.nearby

import android.net.Uri
import com.flowshare.app.core.FileMeta

enum class PeerStatus { AVAILABLE, CONNECTING, CONNECTED, BUSY }

data class Peer(
    val endpointId: String,
    val name: String,
    val token: String?,
    val status: PeerStatus = PeerStatus.AVAILABLE,
    val foundAt: Long = System.currentTimeMillis(),
)

enum class Direction { SEND, RECEIVE }

enum class SessionState {
    CONNECTING, WAITING_ACCEPT, TRANSFERRING, COMPLETE, DECLINED, FAILED, CANCELLED;

    val isLive get() = this == CONNECTING || this == WAITING_ACCEPT || this == TRANSFERRING
    val isEnded get() = !isLive
}

enum class FileStatus { QUEUED, ACTIVE, DONE, FAILED }

data class FileTransfer(
    val meta: FileMeta,
    val transferred: Long = 0,
    val status: FileStatus = FileStatus.QUEUED,
    val savedUri: Uri? = null,
)

data class TransferSession(
    val endpointId: String,
    val peerName: String,
    val direction: Direction,
    val state: SessionState,
    val files: List<FileTransfer>,
    val authCode: String = "",
    val bytesPerSecond: Double = 0.0,
    val verifiedByQr: Boolean = false,
    val message: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
) {
    val totalBytes: Long get() = files.sumOf { it.meta.size.coerceAtLeast(0) }
    val transferredBytes: Long get() = files.sumOf { it.transferred }
    val progress: Float
        get() = when {
            state == SessionState.COMPLETE -> 1f
            totalBytes <= 0 -> if (files.isNotEmpty()) files.count { it.status == FileStatus.DONE } / files.size.toFloat() else 0f
            else -> (transferredBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        }
    val etaSeconds: Long
        get() = if (bytesPerSecond < 1) -1 else ((totalBytes - transferredBytes).coerceAtLeast(0) / bytesPerSecond).toLong()
    val currentIndex: Int
        get() = files.indexOfFirst { it.status == FileStatus.ACTIVE }.takeIf { it >= 0 }
            ?: files.indexOfFirst { it.status == FileStatus.QUEUED }.takeIf { it >= 0 }
            ?: (files.size - 1)
    val doneCount: Int get() = files.count { it.status == FileStatus.DONE }
}

data class IncomingOffer(
    val endpointId: String,
    val senderName: String,
    val authCode: String,
    val files: List<FileMeta>,
    val verifiedByQr: Boolean,
) {
    val totalBytes: Long get() = files.sumOf { it.size.coerceAtLeast(0) }
}

sealed interface FlowEvent {
    /** Two phones just established an encrypted link — play the liquid contact bloom. */
    data class Contact(val endpointId: String, val incoming: Boolean) : FlowEvent
    data class Notice(val message: String) : FlowEvent
}

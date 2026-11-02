package com.openobd.core.transport

import kotlinx.coroutines.channels.Channel

/**
 * A byte pipe to an ELM327-compatible adapter. Implementations exist for Bluetooth Classic (SPP),
 * Bluetooth Low Energy, Wi-Fi (TCP) and a built-in simulator.
 *
 * Bytes received from the adapter must be pushed into [incoming]; the ELM layer consumes them.
 */
abstract class ObdTransport {
    /** Human readable description, e.g. "OBDII (00:1D:A5:68:98:8B)". */
    abstract val description: String

    /** Bytes coming from the adapter. Unlimited so a reader thread never blocks. */
    val incoming: Channel<ByteArray> = Channel(Channel.UNLIMITED)

    @Volatile
    var isOpen: Boolean = false
        protected set

    /** Opens the link. Throws on failure. */
    abstract suspend fun open()

    /** Sends raw bytes to the adapter. */
    abstract suspend fun send(data: ByteArray)

    /** Closes the link; must be safe to call more than once. */
    abstract suspend fun close()

    /** Called by implementations when the link drops unexpectedly. */
    var onDisconnected: ((Throwable?) -> Unit)? = null

    protected fun notifyDisconnected(error: Throwable?) {
        if (isOpen) {
            isOpen = false
            onDisconnected?.invoke(error)
        }
    }
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

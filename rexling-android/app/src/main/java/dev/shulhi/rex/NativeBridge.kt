package dev.shulhi.rex

object NativeBridge {
    init { System.loadLibrary("rex_android") }
    @JvmStatic external fun create(): Long
    @JvmStatic external fun command(handle: Long, request: String): String
    @JvmStatic external fun poll(handle: Long, after: Long): String
    @JvmStatic external fun awaitUpdate(handle: Long, after: Long, timeoutMs: Long): Long
    @JvmStatic external fun readUpdate(handle: Long, after: Long): ByteArray
    @JvmStatic external fun uploadCreate(request: String): Long
    @JvmStatic external fun uploadPoll(handle: Long): String
    @JvmStatic external fun uploadDestroy(handle: Long)
    @JvmStatic external fun downloadCreate(request: String): Long
    @JvmStatic external fun downloadPoll(handle: Long): String
    @JvmStatic external fun downloadDestroy(handle: Long)
    @JvmStatic external fun forwardCreate(request: String): Long
    @JvmStatic external fun forwardPoll(handle: Long): String
    @JvmStatic external fun forwardDestroy(handle: Long)
    @JvmStatic external fun networkCommand(request: String): String
    @JvmStatic external fun probeServer(endpoint: String, builtIn: Boolean, key: String): String
    @JvmStatic external fun destroy(handle: Long)
}

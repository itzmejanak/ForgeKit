package com.forgekit.termux.embedded

import java.io.File
import java.io.IOException

/**
 * Native bridge to the clean-room ForgeKit PTY harness (`forgekit_pty.c`).
 *
 * On Android the library is packaged by the NDK build as `libforgekit_pty.so`
 * (arm64-v8a). On a host JVM the exact same C source is compiled with the host
 * toolchain and injected via [loadFrom] — that is what the unit tests use to
 * prove REAL fork/exec/PTY behavior without a device.
 *
 * Not part of any public API surface: callers use [PtyProcess].
 */
internal object NativePty {

    @Volatile
    private var loaded = false

    /** Returns jintArray { pid, masterFd }. rawMode selects a binary-safe raw termios. */
    external fun nativeForkExec(
        argv: Array<String>,
        env: Array<String>,
        cwd: String,
        rows: Int,
        cols: Int,
        rawMode: Boolean,
    ): IntArray

    /** n>0 bytes read | -1 EOF | -2 timeout/EINTR (safe retry). */
    external fun nativeRead(fd: Int, buf: ByteArray, off: Int, len: Int, timeoutMs: Int): Int

    /** Fully writes; throws IOException when the slave side is gone. */
    external fun nativeWrite(fd: Int, buf: ByteArray, off: Int, len: Int): Int

    /** Raw wait status, or -1 while the child runs (WNOHANG). */
    external fun nativeWaitPid(pid: Int, blocking: Boolean): Int

    /** 0 on success, -1 when the target is already gone (ESRCH). */
    external fun nativeKill(pid: Int, signal: Int): Int

    /** TIOCSWINSZ. */
    external fun nativeSetWindowSize(fd: Int, rows: Int, cols: Int)

    /** close(2) with EINTR retry. */
    external fun nativeClose(fd: Int)

    /** Loads the packaged Android library (libforgekit_pty.so). */
    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("forgekit_pty")
            loaded = true
        }
    }

    /** Host-JVM injection point used by unit tests (real fork/pty on the host). */
    @Synchronized
    fun loadFrom(file: File) {
        if (!loaded) {
            if (!file.isFile) throw IOException("host PTY library not found: $file")
            System.load(file.absolutePath)
            loaded = true
        }
    }
}

package com.forgekit.core.model

/** Normalized process exit status. Exit code 0 = success; anything else is a failure with the raw code. */
public sealed interface ExitStatus {
    /** Process finished. `code` follows POSIX: 0 = success, 1..255 = failure, >255 signals exit(3)-style truncation. */
    public data class Exited(val code: Int) : ExitStatus {
        public val successful: Boolean get() = code == 0
    }

    /** Process terminated by signal (SIGKILL=9, SIGTERM=15, …). */
    public data class Signaled(val signal: Int) : ExitStatus

    /** Process could not be started or its status is unknown (crash of the harness itself). */
    public data class Unknown(val reason: String) : ExitStatus

    public companion object {
        /** Builds an [ExitStatus] from a raw process exit value (Process.waitFor()). */
        public fun fromRaw(raw: Int): ExitStatus =
            if (raw in 0..255) Exited(raw) else Unknown("raw exit value $raw out of POSIX range")

        /** Builds an [ExitStatus] from waitpid-style raw status as returned by the JNI harness. */
        public fun fromWaitStatus(waitStatus: Int): ExitStatus {
            if (waitStatus < 0) return Unknown("waitpid error $waitStatus")
            val signaled = waitStatus and 0x7f
            return if (signaled != 0) {
                Signaled(signaled)
            } else {
                Exited((waitStatus shr 8) and 0xff)
            }
        }
    }
}

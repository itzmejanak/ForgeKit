/*
 * ForgeKit embedded PTY harness — clean-room JNI implementation.
 *
 * Pure POSIX: openpty + fork + execve. Compiles for Android
 * (bionic provides openpty in libc since API 23; minSdk 24) and for host
 * Linux (glibc, link -lutil) where the SAME code backs the JVM unit tests,
 * proving real fork/exec/PTY semantics without a device.
 *
 * Conventions:
 *  - nativeRead returns:  n>0 bytes read | -1 EOF | -2 timeout/EINTR (retry)
 *    and throws java.io.IOException on real errors.
 *  - nativeWaitPid returns the raw wait status, or -1 while the child runs
 *    (WNOHANG); the Kotlin layer decodes it.
 *  - The child between fork and execve only calls async-signal-safe functions
 *    (sigprocmask, chdir, execve, _exit) — never JVM code, never stdio.
 */
#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <pty.h>
#include <sys/stat.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define FORGEKIT_MAX_IO (1 << 20) /* 1 MiB cap per read/write call */

static void throw_io(JNIEnv *env, const char *prefix, int err) {
    if ((*env)->ExceptionCheck(env)) return;
    char buf[512];
    snprintf(buf, sizeof buf, "%s: %s", prefix, strerror(err));
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, buf);
    (*env)->DeleteLocalRef(env, cls);
}

static void throw_illegal_arg(JNIEnv *env, const char *msg) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, msg);
    (*env)->DeleteLocalRef(env, cls);
}

/*
 * Class:     com_forgekit_termux_embedded_NativePty
 * Method:    nativeForkExec
 * Signature: ([Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;IIZ)[I
 *
 * Returns jintArray { pid, masterFd }. Throws IOException when fork/pty fails.
 * rawMode=1 puts the pty line discipline in raw mode (binary-safe stdin for
 * protocol sessions); rawMode=0 keeps the default cooked termios (terminal).
 */
JNIEXPORT jintArray JNICALL
Java_com_forgekit_termux_embedded_NativePty_nativeForkExec(
        JNIEnv *env, jobject thiz,
        jobjectArray argv, jobjectArray envp, jstring jcwd,
        jint rows, jint cols, jboolean rawMode) {
    (void) thiz;
    if (argv == NULL || envp == NULL || jcwd == NULL) {
        throw_illegal_arg(env, "argv, envp and cwd must not be null");
        return NULL;
    }
    jsize argc = (*env)->GetArrayLength(env, argv);
    jsize envc = (*env)->GetArrayLength(env, envp);
    if (argc < 1 || envc < 0) {
        throw_illegal_arg(env, "argv must contain at least the executable");
        return NULL;
    }

    /* All allocation happens BEFORE fork so the child never touches the heap. */
    char **c_argv = (char **) calloc((size_t) argc + 1, sizeof(char *));
    char **c_envp = (char **) calloc((size_t) envc + 1, sizeof(char *));
    if (c_argv == NULL || c_envp == NULL) {
        free(c_argv); free(c_envp);
        throw_io(env, "fork failed to prepare argv/env", ENOMEM);
        return NULL;
    }
    int failed = 0;
    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, argv, i);
        const char *u = (*env)->GetStringUTFChars(env, s, NULL);
        c_argv[i] = u ? strdup(u) : NULL;
        if (u) (*env)->ReleaseStringUTFChars(env, s, u);
        (*env)->DeleteLocalRef(env, s);
        if (c_argv[i] == NULL) { failed = 1; break; }
    }
    for (jsize i = 0; !failed && i < envc; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, envp, i);
        const char *u = (*env)->GetStringUTFChars(env, s, NULL);
        c_envp[i] = u ? strdup(u) : NULL;
        if (u) (*env)->ReleaseStringUTFChars(env, s, u);
        (*env)->DeleteLocalRef(env, s);
        if (c_envp[i] == NULL) { failed = 1; break; }
    }
    const char *c_cwd = (*env)->GetStringUTFChars(env, jcwd, NULL);
    if (c_cwd == NULL) failed = 1;

    if (failed) {
        if (c_cwd) (*env)->ReleaseStringUTFChars(env, jcwd, c_cwd);
        for (jsize i = 0; i < argc; i++) free(c_argv[i]);
        for (jsize i = 0; i < envc; i++) free(c_envp[i]);
        free(c_argv); free(c_envp);
        throw_io(env, "fork failed to prepare argv/env", ENOMEM);
        return NULL;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof ws);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);

    /*
     * Manual openpty + fork (NOT forkpty):
     *  - the master gets FD_CLOEXEC BEFORE the fork, so the exec'd child can
     *    never inherit the master fd (an inherited master keeps the pty alive
     *    behind the parent's back and breaks HUP/EOF semantics);
     *  - the child does setsid + TIOCSCTTY (controlling terminal) and dups the
     *    slave onto 0/1/2 exactly like forkpty would;
     *  - the child closes EVERY other inherited fd above 2 so the runtime can
     *    never leak JVM descriptors (fds, sockets, files) into $PREFIX procs.
     */
    int master = -1, slave = -1;
    struct termios rawtio;
    struct termios *tio = NULL;
    if (rawMode) {
        /* Deterministic raw termios: 8N1, receive enabled, no echo, no
           canonical processing, no signal generation — binary-safe stdin. */
        memset(&rawtio, 0, sizeof rawtio);
        rawtio.c_cflag = (tcflag_t) (CS8 | CREAD | B38400);
        rawtio.c_iflag = 0;
        rawtio.c_oflag = 0;
        rawtio.c_lflag = 0;
        rawtio.c_cc[VMIN] = 1;
        rawtio.c_cc[VTIME] = 0;
        tio = &rawtio;
    }
    if (openpty(&master, &slave, NULL, tio, &ws) != 0) {
        int err = errno;
        if (c_cwd) (*env)->ReleaseStringUTFChars(env, jcwd, c_cwd);
        for (jsize i = 0; i < argc; i++) free(c_argv[i]);
        for (jsize i = 0; i < envc; i++) free(c_envp[i]);
        free(c_argv); free(c_envp);
        throw_io(env, "openpty failed", err);
        return NULL;
    }
    int cloexec = fcntl(master, F_GETFD);
    if (cloexec >= 0) (void) fcntl(master, F_SETFD, cloexec | FD_CLOEXEC);
    int scloexec = fcntl(slave, F_GETFD);
    if (scloexec >= 0) (void) fcntl(slave, F_SETFD, scloexec | FD_CLOEXEC);

    /*
     * Block EVERY signal on the forking thread BEFORE fork(): the child then
     * starts life with a full block — there is no window (not even a few
     * instructions) in which the JVM's inherited signal handlers could run
     * and swallow a kill aimed at the future runtime process.
     */
    sigset_t all, oldmask;
    sigfillset(&all);
    sigprocmask(SIG_BLOCK, &all, &oldmask);

    pid_t pid = fork();

    /* parent: restore the thread's original mask immediately */
    if (pid != 0) sigprocmask(SIG_SETMASK, &oldmask, NULL);

    if (pid < 0) {
        int err = errno;
        for (jsize i = 0; i < argc; i++) free(c_argv[i]);
        for (jsize i = 0; i < envc; i++) free(c_envp[i]);
        free(c_argv); free(c_envp);
        close(master); close(slave);
        throw_io(env, "fork failed", err);
        return NULL;
    }

    if (pid == 0) {
        /* ---- child: signals blocked since the first instruction ----
         *
         * Canonical fork-exec hygiene (same as glibc system(3)):
         *   1. ALL signals are blocked (inherited from the pre-fork mask —
         *      there was never an unblocked moment after fork).
         *   2. RESET every disposition to SIG_DFL: kills that arrive while
         *      blocked stay pending and, once unblocked below, take the
         *      DEFAULT action — exactly what a kill() caller expects.
         *   3. unblock only right before execve. */
        for (int sig = 1; sig < 64; sig++) {
            struct sigaction sa;
            memset(&sa, 0, sizeof sa);
            sa.sa_handler = SIG_DFL;
            (void) sigaction(sig, &sa, NULL);
        }

        close(master); /* never let the child own the master side */

        if (setsid() < 0) _exit(126);
        if (ioctl(slave, TIOCSCTTY, (void *) 0) != 0) _exit(126);

        if (dup2(slave, 0) < 0 || dup2(slave, 1) < 0 || dup2(slave, 2) < 0) _exit(126);
        if (slave > 2) close(slave);

        /* close every other inherited fd so nothing leaks into the runtime */
        int maxfd = (int) sysconf(_SC_OPEN_MAX);
        if (maxfd > 4096) maxfd = 4096; /* bounded sweep; JVMs open few fds */
        for (int fdi = 3; fdi < maxfd; fdi++) close(fdi);

        if (chdir(c_cwd) != 0) _exit(126);

        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, NULL);

        execve(c_argv[0], c_argv, c_envp);
        _exit(127); /* exec failed: no JVM code may run past this point */
    }

    /* ---- parent ---- */
    close(slave); /* the parent never reads/writes the slave side */
    if (c_cwd) (*env)->ReleaseStringUTFChars(env, jcwd, c_cwd);
    for (jsize i = 0; i < argc; i++) free(c_argv[i]);
    for (jsize i = 0; i < envc; i++) free(c_envp[i]);
    free(c_argv); free(c_envp);

    /* master stays open in the JVM (CLOEXEC was set before the fork so
       future execs of THIS process never inherit it). */

    jint out[2];
    out[0] = (jint) pid;
    out[1] = master;
    jintArray res = (*env)->NewIntArray(env, 2);
    if (res == NULL) {
        close(master);
        (void) kill(pid, SIGKILL);
        return NULL; /* OOM already pending */
    }
    (*env)->SetIntArrayRegion(env, res, 0, 2, out);
    return res;
}

/*
 * Class:     com_forgekit_termux_embedded_NativePty
 * Method:    nativeRead
 * Returns n>0 (bytes), -1 (EOF), -2 (timeout / EINTR — safe retry).
 */
JNIEXPORT jint JNICALL
Java_com_forgekit_termux_embedded_NativePty_nativeRead(
        JNIEnv *env, jobject thiz,
        jint fd, jbyteArray buf, jint off, jint len, jint timeoutMs) {
    (void) thiz;
    if (buf == NULL) { throw_illegal_arg(env, "buf must not be null"); return -1; }
    if (off < 0 || len < 0 || len > FORGEKIT_MAX_IO) {
        throw_illegal_arg(env, "bad offset/length");
        return -1;
    }
    if (len == 0) return 0;

    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = (short) (POLLIN | POLLHUP | POLLERR);
    pfd.revents = 0;
    int pr = poll(&pfd, 1, timeoutMs);
    if (pr < 0) {
        if (errno == EINTR) return -2;
        throw_io(env, "pty poll failed", errno);
        return -1;
    }
    if (pr == 0) return -2; /* timeout */

    unsigned char *tmp = (unsigned char *) malloc((size_t) len);
    if (tmp == NULL) {
        throw_io(env, "pty read alloc failed", ENOMEM);
        return -1;
    }
    ssize_t n = read(fd, tmp, (size_t) len);
    if (n < 0) {
        int err = errno;
        free(tmp);
        if (err == EINTR) return -2;
        if (err == EAGAIN || err == EWOULDBLOCK) return -2;
        if (err == EIO) return -1; /* Linux pty master: slave side gone = EOF */
        throw_io(env, "pty read failed", err);
        return -1;
    }
    if (n == 0) {
        free(tmp);
        return -1; /* EOF: slave side fully closed */
    }
    (*env)->SetByteArrayRegion(env, buf, off, (jsize) n, (const jbyte *) tmp);
    free(tmp);
    return (jint) n;
}

/*
 * Class:     com_forgekit_termux_embedded_NativePty
 * Method:    nativeWrite
 * Fully writes the buffer (EINTR/EAGAIN retried). Throws IOException when
 * the slave side is gone (EIO/EPIPE on Linux ptys).
 */
JNIEXPORT jint JNICALL
Java_com_forgekit_termux_embedded_NativePty_nativeWrite(
        JNIEnv *env, jobject thiz,
        jint fd, jbyteArray buf, jint off, jint len) {
    (void) thiz;
    if (buf == NULL) { throw_illegal_arg(env, "buf must not be null"); return -1; }
    if (off < 0 || len < 0 || len > FORGEKIT_MAX_IO) {
        throw_illegal_arg(env, "bad offset/length");
        return -1;
    }
    if (len == 0) return 0;

    unsigned char *tmp = (unsigned char *) malloc((size_t) len);
    if (tmp == NULL) {
        throw_io(env, "pty write alloc failed", ENOMEM);
        return -1;
    }
    (*env)->GetByteArrayRegion(env, buf, off, len, (jbyte *) tmp);

    size_t written = 0;
    while (written < (size_t) len) {
        ssize_t n = write(fd, tmp + written, (size_t) len - written);
        if (n > 0) {
            written += (size_t) n;
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            struct pollfd pfd;
            pfd.fd = fd;
            pfd.events = (short) (POLLOUT | POLLHUP | POLLERR);
            pfd.revents = 0;
            int pr = poll(&pfd, 1, 1000);
            if (pr < 0 && errno == EINTR) continue;
            if (pr > 0) continue;   /* writable (or hup: next write errors) */
            free(tmp);
            throw_io(env, "pty write blocked", pr < 0 ? errno : ETIMEDOUT);
            return -1;
        }
        int err = (n < 0) ? errno : EIO;
        free(tmp);
        throw_io(env, "pty write failed (process closed its stdin?)", err);
        return -1;
    }
    free(tmp);
    return (jint) written;
}

/*
 * Class:     com_forgekit_termux_embedded_NativePty
 * Method:    nativeWaitPid
 * Returns the raw wait status, or -1 while the child still runs (WNOHANG).
 * blocking=1 waits; EINTR retried. Throws on wait errors (e.g. ECHILD).
 */
JNIEXPORT jint JNICALL
Java_com_forgekit_termux_embedded_NativePty_nativeWaitPid(
        JNIEnv *env, jobject thiz, jint pid, jboolean blocking) {
    (void) thiz;
    for (;;) {
        int status = 0;
        int options = blocking ? 0 : WNOHANG;
        pid_t r = waitpid((pid_t) pid, &status, options);
        if (r == (pid_t) pid) return status;
        if (r == 0) return -1; /* still running */
        if (r < 0 && errno == EINTR) continue;
        if (r < 0) {
            throw_io(env, "waitpid failed", errno);
            return -1;
        }
    }
}

/*
 * Class:     com_forgekit_termux_embedded_NativePty
 * Method:    nativeKill
 * Returns 0 on success, -1 when the process is already gone (ESRCH).
 * Permission problems throw.
 */
JNIEXPORT jint JNICALL
Java_com_forgekit_termux_embedded_NativePty_nativeKill(
        JNIEnv *env, jobject thiz, jint pid, jint sig) {
    (void) thiz;
    if (kill((pid_t) pid, sig) == 0) return 0;
    if (errno == ESRCH) return -1;
    throw_io(env, "kill failed", errno);
    return -1;
}

/*
 * Class:     com_forgekit_termux_embedded_NativePty
 * Method:    nativeSetWindowSize
 * TIOCSWINSZ on the master; the kernel relays SIGWINCH to the foreground
 * process group of the slave automatically.
 */
JNIEXPORT void JNICALL
Java_com_forgekit_termux_embedded_NativePty_nativeSetWindowSize(
        JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    (void) thiz;
    struct winsize ws;
    memset(&ws, 0, sizeof ws);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    if (ioctl(fd, TIOCSWINSZ, &ws) != 0) {
        throw_io(env, "TIOCSWINSZ failed", errno);
    }
}

/*
 * Class:     com_forgekit_termux_embedded_NativePty
 * Method:    nativeClose
 */
JNIEXPORT void JNICALL
Java_com_forgekit_termux_embedded_NativePty_nativeClose(
        JNIEnv *env, jobject thiz, jint fd) {
    (void) env; (void) thiz;
    while (close(fd) != 0 && errno == EINTR) { /* retry */ }
}

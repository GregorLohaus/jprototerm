package com.gregor.jprototerm;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A Linux PTY backed by libc via the Foreign Function & Memory API.
 *
 * <p>This replaces pty4j (which loads a JNA JNI shim). It uses
 * {@code posix_openpt}/{@code posix_spawnp} rather than {@code fork}/{@code forkpty}:
 * doing work between {@code fork} and {@code exec} inside a multithreaded JVM is unsafe
 * (only async-signal-safe calls are permitted), whereas {@code posix_spawn} performs the
 * dangerous part in libc with no Java on the stack.
 *
 * <p>The child gets a fresh session via {@code POSIX_SPAWN_SETSID}; it then opens the slave
 * PTY itself (as fd 0, without {@code O_NOCTTY}) so the slave becomes its controlling
 * terminal. glibc applies attribute flags (the setsid) before file actions, so the open
 * happens in the new session.
 */
public final class LinuxPty implements AutoCloseable {
    static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();
    private static final ExecutorService REAPER = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "pty-reaper");
        thread.setDaemon(true);
        return thread;
    });

    static final AddressLayout C_POINTER = (AddressLayout) LINKER.canonicalLayouts().get("void*");
    static final ValueLayout.OfShort C_SHORT = (ValueLayout.OfShort) LINKER.canonicalLayouts().get("short");
    static final ValueLayout.OfInt C_INT = (ValueLayout.OfInt) LINKER.canonicalLayouts().get("int");
    static final ValueLayout.OfLong C_LONG = (ValueLayout.OfLong) LINKER.canonicalLayouts().get("long");
    static final ValueLayout.OfLong C_SIZE_T = (ValueLayout.OfLong) LINKER.canonicalLayouts().get("size_t");

    // Function descriptors.
    static final FunctionDescriptor FD_INT_INT = FunctionDescriptor.of(C_INT, C_INT);
    static final FunctionDescriptor FD_PTSNAME_R = FunctionDescriptor.of(C_INT, C_INT, C_POINTER, C_SIZE_T);
    static final FunctionDescriptor FD_RW = FunctionDescriptor.of(C_LONG, C_INT, C_POINTER, C_SIZE_T);
    static final FunctionDescriptor FD_IOCTL = FunctionDescriptor.of(C_INT, C_INT, C_LONG, C_POINTER);
    static final FunctionDescriptor FD_KILL = FunctionDescriptor.of(C_INT, C_INT, C_INT);
    static final FunctionDescriptor FD_WAITPID = FunctionDescriptor.of(C_INT, C_INT, C_POINTER, C_INT);
    static final FunctionDescriptor FD_SPAWN = FunctionDescriptor.of(
            C_INT, C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_POINTER, C_POINTER);
    static final FunctionDescriptor FD_FA_INIT = FunctionDescriptor.of(C_INT, C_POINTER);
    static final FunctionDescriptor FD_FA_ADDCLOSE = FunctionDescriptor.of(C_INT, C_POINTER, C_INT);
    static final FunctionDescriptor FD_FA_ADDDUP2 = FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_INT);
    static final FunctionDescriptor FD_FA_ADDOPEN =
            FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_POINTER, C_INT, C_INT);
    static final FunctionDescriptor FD_FA_ADDCHDIR = FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER);
    static final FunctionDescriptor FD_ATTR_SETFLAGS = FunctionDescriptor.of(C_INT, C_POINTER, C_SHORT);

    // Linux constants (x86-64 / arm64).
    private static final int O_RDWR = 0x0002;
    private static final int O_NOCTTY = 0x0100;
    private static final long TIOCSWINSZ = 0x5414L;
    private static final short POSIX_SPAWN_SETSID = 0x80;
    private static final int SIGHUP = 1;
    private static final int SIGINT = 2;
    private static final int SIGQUIT = 3;
    private static final int SIGKILL = 9;
    private static final int SIGTERM = 15;
    private static final int WNOHANG = 1;

    // struct winsize { unsigned short ws_row, ws_col, ws_xpixel, ws_ypixel; }
    private static final MemoryLayout WINSIZE = MemoryLayout.structLayout(
            C_SHORT.withName("ws_row"),
            C_SHORT.withName("ws_col"),
            C_SHORT.withName("ws_xpixel"),
            C_SHORT.withName("ws_ypixel"));

    // posix_spawn_file_actions_t / posix_spawnattr_t are opaque; over-allocate generously.
    private static final long SPAWN_ACTIONS_SIZE = 256;
    private static final long SPAWN_ATTR_SIZE = 512;

    private static final MethodHandle TCGETPGRP = handle("tcgetpgrp", FD_INT_INT);
    private static final MethodHandle POSIX_OPENPT = handle("posix_openpt", FD_INT_INT);
    private static final MethodHandle GRANTPT = handle("grantpt", FD_INT_INT);
    private static final MethodHandle UNLOCKPT = handle("unlockpt", FD_INT_INT);
    private static final MethodHandle PTSNAME_R = handle("ptsname_r", FD_PTSNAME_R);
    private static final MethodHandle CLOSE = handle("close", FD_INT_INT);
    private static final MethodHandle READ = handle("read", FD_RW);
    private static final MethodHandle WRITE = handle("write", FD_RW);
    private static final MethodHandle IOCTL = handle("ioctl", FD_IOCTL, Linker.Option.firstVariadicArg(2));
    private static final MethodHandle KILL = handle("kill", FD_KILL);
    private static final MethodHandle WAITPID = handle("waitpid", FD_WAITPID);
    private static final MethodHandle POSIX_SPAWNP = handle("posix_spawnp", FD_SPAWN);
    private static final MethodHandle FA_INIT = handle("posix_spawn_file_actions_init", FD_FA_INIT);
    private static final MethodHandle FA_DESTROY = handle("posix_spawn_file_actions_destroy", FD_FA_INIT);
    private static final MethodHandle FA_ADDCLOSE = handle("posix_spawn_file_actions_addclose", FD_FA_ADDCLOSE);
    private static final MethodHandle FA_ADDDUP2 = handle("posix_spawn_file_actions_adddup2", FD_FA_ADDDUP2);
    private static final MethodHandle FA_ADDOPEN = handle("posix_spawn_file_actions_addopen", FD_FA_ADDOPEN);
    private static final MethodHandle FA_ADDCHDIR = handle("posix_spawn_file_actions_addchdir_np", FD_FA_ADDCHDIR);
    private static final MethodHandle ATTR_INIT = handle("posix_spawnattr_init", FD_FA_INIT);
    private static final MethodHandle ATTR_DESTROY = handle("posix_spawnattr_destroy", FD_FA_INIT);
    private static final MethodHandle ATTR_SETFLAGS = handle("posix_spawnattr_setflags", FD_ATTR_SETFLAGS);

    private final Arena arena = Arena.ofShared();
    private final MemorySegment readBuffer = arena.allocate(65536);
    private final MemorySegment writeBuffer = arena.allocate(65536);
    private final Object writeLock = new Object();
    private final int masterFd;
    private final int pid;
    private final int closeSignal;
    private volatile boolean closed;

    private LinuxPty(int masterFd, int pid, int closeSignal) {
        this.masterFd = masterFd;
        this.pid = pid;
        this.closeSignal = closeSignal;
    }

    /**
     * Resolves a signal name (e.g. {@code "SIGTERM"}, {@code "TERM"}, {@code "SIGKILL"}) to its
     * Linux signal number, or {@code -1} if the name is not one we recognise. Case-insensitive and
     * tolerant of a missing {@code SIG} prefix.
     */
    public static int signalNumber(String name) {
        if (name == null) {
            return -1;
        }
        String normalized = name.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.startsWith("SIG")) {
            normalized = normalized.substring(3);
        }
        return switch (normalized) {
            case "HUP" -> SIGHUP;
            case "INT" -> SIGINT;
            case "QUIT" -> SIGQUIT;
            case "KILL" -> SIGKILL;
            case "TERM" -> SIGTERM;
            default -> -1;
        };
    }

    /**
     * Opens a PTY and spawns {@code argv} attached to its slave end.
     *
     * @param argv command and arguments (e.g. {@code {"/bin/zsh", "-i"}})
     * @param environment environment for the child, as KEY=VALUE pairs
     * @param workingDirectory directory the child starts in, or {@code null} to inherit
     * @param closeSignal signal number sent to the child on {@link #close()} (e.g. SIGTERM)
     */
    public static LinuxPty spawn(String[] argv, Map<String, String> environment, String workingDirectory,
            int closeSignal) {
        Arena setup = Arena.ofConfined();
        try {
            int master = check(callInt(POSIX_OPENPT, O_RDWR | O_NOCTTY), "posix_openpt");
            try {
                check(callInt(GRANTPT, master), "grantpt");
                check(callInt(UNLOCKPT, master), "unlockpt");

                MemorySegment nameBuf = setup.allocate(256);
                check(callPtsnameR(master, nameBuf), "ptsname_r");
                String slavePath = nameBuf.getString(0);

                MemorySegment actions = setup.allocate(SPAWN_ACTIONS_SIZE);
                MemorySegment attr = setup.allocate(SPAWN_ATTR_SIZE);
                check(callInt(FA_INIT, actions), "posix_spawn_file_actions_init");
                check(callInt(ATTR_INIT, attr), "posix_spawnattr_init");
                try {
                    check(callInt(ATTR_SETFLAGS, attr, POSIX_SPAWN_SETSID), "posix_spawnattr_setflags");

                    if (workingDirectory != null) {
                        MemorySegment dir = setup.allocateFrom(workingDirectory);
                        check(callAddChdir(actions, dir), "posix_spawn_file_actions_addchdir_np");
                    }
                    // Open the slave as fd 0 in the new session -> controlling terminal, then fan out.
                    MemorySegment slave = setup.allocateFrom(slavePath);
                    check(callAddOpen(actions, 0, slave, O_RDWR, 0), "posix_spawn_file_actions_addopen");
                    check(callAddDup2(actions, 0, 1), "posix_spawn_file_actions_adddup2");
                    check(callAddDup2(actions, 0, 2), "posix_spawn_file_actions_adddup2");
                    check(callAddClose(actions, master), "posix_spawn_file_actions_addclose");

                    MemorySegment argvSeg = cStringArray(setup, List.of(argv));
                    MemorySegment envpSeg = cStringArray(setup, toEnvList(environment));
                    MemorySegment path = setup.allocateFrom(argv[0]);
                    MemorySegment pidOut = setup.allocate(C_INT);

                    int rc = callSpawn(pidOut, path, actions, attr, argvSeg, envpSeg);
                    if (rc != 0) {
                        throw new IllegalStateException("posix_spawnp failed for " + argv[0] + " (rc=" + rc + ")");
                    }
                    return new LinuxPty(master, pidOut.get(C_INT, 0), closeSignal);
                } finally {
                    callInt(ATTR_DESTROY, attr);
                    callInt(FA_DESTROY, actions);
                }
            } catch (RuntimeException ex) {
                callInt(CLOSE, master);
                throw ex;
            }
        } finally {
            setup.close();
        }
    }

    /** Reads available output into {@code dst}; returns bytes read, or -1 at EOF. */
    public int read(byte[] dst) {
        if (closed) {
            return -1;
        }
        long n = callLong(READ, masterFd, readBuffer, Math.min(dst.length, readBuffer.byteSize()));
        if (n <= 0) {
            return -1;
        }
        MemorySegment.copy(readBuffer, ValueLayout.JAVA_BYTE, 0, dst, 0, (int) n);
        return (int) n;
    }

    /** Writes all of {@code data} to the master end. */
    public void write(byte[] data) {
        if (closed || data.length == 0) {
            return;
        }
        synchronized (writeLock) {
            int offset = 0;
            while (offset < data.length) {
                int chunk = (int) Math.min(writeBuffer.byteSize(), data.length - offset);
                MemorySegment.copy(data, offset, writeBuffer, ValueLayout.JAVA_BYTE, 0, chunk);

                long written = 0;
                while (written < chunk) {
                    long n = callLong(WRITE, masterFd, writeBuffer.asSlice(written), chunk - written);
                    if (n <= 0) {
                        throw new IllegalStateException("write to pty failed");
                    }
                    written += n;
                }
                offset += chunk;
            }
        }
    }

    /**
     * Best-effort current working directory of the terminal's foreground process group, read from
     * {@code /proc}. This tracks the directory the user is actually in (a {@code cd} in the shell,
     * or a child program that changed dir), so a newly opened pane can start there. Falls back to
     * the shell's own pid, and returns {@code null} if it cannot be determined.
     */
    public String currentWorkingDirectory() {
        if (closed) {
            return null;
        }
        int pgid = callInt(TCGETPGRP, masterFd);
        int target = pgid > 0 ? pgid : pid;
        try {
            return Files.readSymbolicLink(Path.of("/proc", Integer.toString(target), "cwd")).toString();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /** Resizes the terminal window. */
    public void setWinSize(int columns, int rows) {
        if (closed) {
            return;
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ws = a.allocate(WINSIZE);
            ws.set(C_SHORT, 0, (short) rows);
            ws.set(C_SHORT, 2, (short) columns);
            ws.set(C_SHORT, 4, (short) 0);
            ws.set(C_SHORT, 6, (short) 0);
            callIoctl(masterFd, TIOCSWINSZ, ws);
        }
    }

    @Override
    public void close() {
        if (!markClosed()) {
            return;
        }
        closeMaster();
        try {
            reap();
        } finally {
            arena.close();
        }
    }

    /** Send the configured close signal and close the master fd now; reap off the caller thread. */
    public void closeDetached() {
        if (!markClosed()) {
            return;
        }
        closeMaster();
        REAPER.submit(() -> {
            try {
                reap();
            } finally {
                arena.close();
            }
        });
    }

    private synchronized boolean markClosed() {
        if (closed) {
            return false;
        }
        closed = true;
        return true;
    }

    private void closeMaster() {
        // Note: closing the master fd does NOT wake a reader thread blocked in read() on it —
        // the reader unblocks via EOF when the child exits and the slave end closes. The signal
        // here usually does that; if the child ignores it, the SIGKILL escalation in reap()
        // guarantees it shortly after.
        callKill(pid, closeSignal);
        callInt(CLOSE, masterFd);
    }

    private void reap() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment status = a.allocate(C_INT);
            // Closing the master sends EOF/SIGHUP; an interactive shell exits promptly.
            for (int attempt = 0; attempt < 50; attempt++) {
                int r = callWaitpid(pid, status, WNOHANG);
                if (r != 0) {
                    return; // reaped, or no such child
                }
                if (attempt == 25) {
                    callKill(pid, SIGKILL);
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // --- typed invokeExact wrappers ---------------------------------------------------------

    private static int callInt(MethodHandle handle, int arg) {
        try {
            return (int) handle.invokeExact(arg);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    private static int callInt(MethodHandle handle, MemorySegment arg) {
        try {
            return (int) handle.invokeExact(arg);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    private static int callInt(MethodHandle handle, MemorySegment a, short b) {
        try {
            return (int) handle.invokeExact(a, b);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    private static long callLong(MethodHandle handle, int fd, MemorySegment buf, long len) {
        try {
            return (long) handle.invokeExact(fd, buf, len);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    private static int callPtsnameR(int fd, MemorySegment buf) {
        try {
            return (int) PTSNAME_R.invokeExact(fd, buf, buf.byteSize());
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    private static int callAddChdir(MemorySegment actions, MemorySegment path) {
        try {
            return (int) FA_ADDCHDIR.invokeExact(actions, path);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    private static int callAddOpen(MemorySegment actions, int fd, MemorySegment path, int oflag, int mode) {
        try {
            return (int) FA_ADDOPEN.invokeExact(actions, fd, path, oflag, mode);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    private static int callAddDup2(MemorySegment actions, int fd, int newFd) {
        try {
            return (int) FA_ADDDUP2.invokeExact(actions, fd, newFd);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    private static int callAddClose(MemorySegment actions, int fd) {
        try {
            return (int) FA_ADDCLOSE.invokeExact(actions, fd);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    private static int callSpawn(MemorySegment pid, MemorySegment path, MemorySegment actions,
                                 MemorySegment attr, MemorySegment argv, MemorySegment envp) {
        try {
            return (int) POSIX_SPAWNP.invokeExact(pid, path, actions, attr, argv, envp);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    private static void callIoctl(int fd, long request, MemorySegment arg) {
        try {
            int unused = (int) IOCTL.invokeExact(fd, request, arg);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    private static void callKill(int pid, int signal) {
        try {
            int unused = (int) KILL.invokeExact(pid, signal);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    private static int callWaitpid(int pid, MemorySegment status, int options) {
        try {
            return (int) WAITPID.invokeExact(pid, status, options);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    private static MethodHandle handle(String symbol, FunctionDescriptor descriptor, Linker.Option... options) {
        MemorySegment address = LIBC.find(symbol)
                .orElseThrow(() -> new IllegalStateException("libc symbol not found: " + symbol));
        return LINKER.downcallHandle(address, descriptor, options);
    }

    private static MemorySegment cStringArray(Arena arena, List<String> values) {
        MemorySegment array = arena.allocate(C_POINTER, values.size() + 1L);
        for (int i = 0; i < values.size(); i++) {
            array.setAtIndex(C_POINTER, i, arena.allocateFrom(values.get(i)));
        }
        array.setAtIndex(C_POINTER, values.size(), MemorySegment.NULL);
        return array;
    }

    private static List<String> toEnvList(Map<String, String> environment) {
        List<String> out = new ArrayList<>(environment.size());
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            out.add(entry.getKey() + "=" + entry.getValue());
        }
        return out;
    }

    private static int check(int rc, String what) {
        if (rc < 0) {
            throw new IllegalStateException(what + " failed (rc=" + rc + ")");
        }
        return rc;
    }

    private static RuntimeException sneaky(Throwable t) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }
}

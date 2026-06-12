package com.gregor.jprototerm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Single-instance server and its thin client, over a per-user Unix domain socket. The server hosts
 * every window in one JVM (see {@link WindowManager}); a client invocation just asks it to open a
 * window in the client's working directory and exits, so the window appears without paying cold
 * JVM/JavaFX/GL startup.
 *
 * <p>Protocol is deliberately trivial: the client writes one UTF-8 line — the absolute working
 * directory — and the server replies {@code OK\n}. The socket lives under {@code XDG_RUNTIME_DIR}
 * (mode 0700), so only the owning user can connect.
 */
public final class Daemon {
    // One request is a single line holding a filesystem path; anything bigger is bogus.
    private static final int MAX_REQUEST_BYTES = 4096;
    // The accept loop is single-threaded, so a client that stalls must not wedge the daemon.
    private static final long READ_TIMEOUT_NANOS = 5_000_000_000L;

    private Daemon() {
    }

    /** Runs the server: brings up the toolkit, binds the socket, and serves window-open requests. */
    public static void run() {
        Path socket = socketPath();
        try {
            Files.createDirectories(socket.getParent());
            secureDir(socket.getParent());
        } catch (IOException ex) {
            System.err.println("jprototerm: cannot secure socket dir " + socket.getParent() + ": " + ex.getMessage());
            return;
        }

        WindowManager manager = WindowManager.start(WindowManager.Mode.DAEMON);

        try (ServerSocketChannel server = bind(socket)) {
            while (true) {
                try {
                    handle(server.accept(), manager);
                } catch (IOException ex) {
                    System.err.println("jprototerm: connection error: " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            System.err.println("jprototerm: daemon socket error: " + ex.getMessage());
        }
    }

    /**
     * Client side: connect to a running daemon and ask it to open a window in {@code workingDirectory}.
     * Returns {@code true} if the daemon handled it, {@code false} if none is reachable (the caller
     * then falls back to a standalone in-process window).
     */
    public static boolean tryClient(String workingDirectory) {
        Path socket = socketPath();
        if (!Files.exists(socket)) {
            return false;
        }
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            channel.write(ByteBuffer.wrap((workingDirectory + "\n").getBytes(StandardCharsets.UTF_8)));
            // Best-effort wait for the ack so we don't race ahead of the window opening.
            channel.read(ByteBuffer.allocate(16));
            return true;
        } catch (IOException ex) {
            return false; // no daemon, or a stale socket file — fall back to standalone
        }
    }

    private static ServerSocketChannel bind(Path socket) throws IOException {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socket);
        ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.bind(address);
            return channel;
        } catch (IOException firstTry) {
            // The path is taken. If a live daemon answers, this invocation lost the race; otherwise
            // it's a stale socket from a crashed daemon, so remove it and rebind.
            if (tryClient0(socket)) {
                channel.close();
                System.err.println("jprototerm: a daemon is already running");
                System.exit(0);
            }
            Files.deleteIfExists(socket);
            channel.bind(address);
            return channel;
        }
    }

    /** A bare connect probe used by {@link #bind} to tell a live daemon from a stale socket file. */
    private static boolean tryClient0(Path socket) {
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static void handle(SocketChannel connection, WindowManager manager) throws IOException {
        try (connection) {
            String workingDirectory = readLine(connection);
            manager.openWindow(workingDirectory == null || workingDirectory.isBlank()
                    ? null
                    : workingDirectory.trim());
            connection.configureBlocking(true);
            connection.write(ByteBuffer.wrap("OK\n".getBytes(StandardCharsets.UTF_8)));
        }
    }

    // Reads the request line non-blocking with a deadline and a size cap: the accept loop is
    // single-threaded, so a client that stalls or never sends a newline must fail the connection
    // (an IOException logged by run()) rather than wedge the daemon or grow the buffer unbounded.
    private static String readLine(SocketChannel channel) throws IOException {
        channel.configureBlocking(false);
        long deadline = System.nanoTime() + READ_TIMEOUT_NANOS;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        while (true) {
            int n = channel.read(buffer);
            if (n > 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    byte b = buffer.get();
                    if (b == '\n') {
                        return out.toString(StandardCharsets.UTF_8);
                    }
                    out.write(b);
                    if (out.size() > MAX_REQUEST_BYTES) {
                        throw new IOException("request line too long");
                    }
                }
                buffer.clear();
            } else if (n == -1) {
                return out.size() == 0 ? null : out.toString(StandardCharsets.UTF_8);
            } else {
                if (System.nanoTime() >= deadline) {
                    throw new IOException("request timed out");
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while reading request");
                }
            }
        }
    }

    private static Path socketPath() {
        String runtimeDir = System.getenv("XDG_RUNTIME_DIR");
        Path dir = runtimeDir != null && !runtimeDir.isBlank()
                ? Path.of(runtimeDir, "jprototerm")
                : Path.of("/tmp", "jprototerm-" + System.getProperty("user.name", "user"));
        return dir.resolve("daemon.sock");
    }

    // Make the socket dir private, and refuse to use it if it is not ours. The /tmp fallback
    // path is predictable, so another user could have pre-created it (the classic /tmp race);
    // binding a socket inside a directory someone else owns would hand them control of it.
    private static void secureDir(Path dir) throws IOException {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            return; // not a POSIX filesystem: nothing more we can check
        }
        String owner = Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS).getName();
        String user = System.getProperty("user.name");
        if (!owner.equals(user)) {
            throw new IOException(dir + " is owned by '" + owner + "', not '" + user + "'");
        }
    }
}

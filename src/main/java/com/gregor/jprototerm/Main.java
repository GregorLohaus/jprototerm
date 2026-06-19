package com.gregor.jprototerm;

/**
 * Entry point and mode dispatch. A bare invocation is a thin client: it hands the request to a
 * running {@link Daemon}, or, if none is reachable, opens a single standalone window in this process
 * (today's behavior). {@code --daemon} runs the long-lived server that hosts every window in one
 * JVM, so client launches skip cold JVM/JavaFX/GL startup. {@code --standalone} skips daemon client
 * mode and always opens an in-process window, which is useful while testing development builds.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        // Match the renderer order the app was tuned for; honor an explicit override if present.
        System.setProperty("prism.order", System.getProperty("prism.order", "es2,sw"));

        boolean standalone = false;
        for (String arg : args) {
            if (arg.equals("--daemon")) {
                Daemon.run();
                return;
            }
            if (arg.equals("--standalone")) {
                standalone = true;
            }
        }

        String workingDirectory = System.getProperty("user.dir");
        if (!standalone && Daemon.tryClient(workingDirectory)) {
            return; // a running daemon opened the window
        }
        // No daemon reachable: fall back to a standalone window; the JVM exits when it closes.
        WindowManager.start(WindowManager.Mode.STANDALONE).openWindow(workingDirectory);
    }
}

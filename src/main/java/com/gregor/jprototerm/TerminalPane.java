package com.gregor.jprototerm;

import dev.jlibghostty.DeviceAttributes;
import dev.jlibghostty.Ghostty;
import dev.jlibghostty.KittyGraphics;
import dev.jlibghostty.MouseAction;
import dev.jlibghostty.MouseEncoder;
import dev.jlibghostty.MouseEncoderSize;
import dev.jlibghostty.MouseInput;
import dev.jlibghostty.RenderState;
import dev.jlibghostty.RenderStateSnapshot;
import dev.jlibghostty.ScrollViewport;
import dev.jlibghostty.Terminal;
import dev.jlibghostty.TerminalOptions;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.shape.Shape;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One terminal: owns its ghostty {@link Terminal}, the {@link ShellSession}/pty driving it,
 * and its on-screen geometry and grid. It does not draw itself — it is a {@link RenderTarget}
 * that a {@link TerminalRenderer} paints. {@link #paintFull}/{@link #paintIncremental} are the
 * only rendering API exposed to the {@link Compositor}, and they just delegate to that
 * renderer; the compositor decides z-order and which rect each pane occupies.
 */
public final class TerminalPane implements AutoCloseable, RenderTarget {
    private final Terminal terminal;
    private final TerminalMetrics metrics;
    private final boolean kittyEnabled;
    // Run on every content change so the owning tab can bump its content version — the
    // compositor's O(1) "did the current tab change?" gate.
    private final Runnable onContentChange;
    private final TerminalRenderer renderer;
    private final MouseEncoder mouseEncoder = new MouseEncoder();
    // A persistent render state (reused across frames) is what makes ghostty's per-row dirty
    // tracking meaningful: update() accumulates dirty since the last resetDirty().
    private final RenderState renderState = new RenderState();
    private RenderStateSnapshot cachedSnapshot;
    private ShellSession session;
    // Clip region for rendering (rect minus the panes covering this one), set at layout time;
    // null means clip to the plain bounds. See RenderTarget#clip().
    private Shape clip;
    private double x;
    private double y;
    private double width;
    private double height;
    private int columns;
    private int rows;
    private int pixelWidth;
    private int pixelHeight;
    private final AtomicLong contentVersion = new AtomicLong();
    private long snapshotVersion = -1;

    private TerminalPane(Terminal terminal, TerminalMetrics metrics, boolean kittyEnabled,
            Runnable onContentChange, TerminalRenderer renderer, int columns, int rows) {
        this.terminal = terminal;
        this.metrics = metrics;
        this.kittyEnabled = kittyEnabled;
        this.onContentChange = onContentChange;
        this.renderer = renderer;
        this.columns = columns;
        this.rows = rows;
    }

    /**
     * Opens a pane sized to fit the given pixel rect: the shared cell metrics decide how many
     * columns and rows fit, and that grid is handed to ghostty and the shell at start-up. A
     * non-positive size falls back to the configured default grid (used before the first
     * layout, when no rect is known yet). The pane owns the shell session it starts and runs
     * {@code onContentChange} on every content change.
     */
    public static TerminalPane create(AppConfig config, TerminalMetrics metrics, Runnable onContentChange, double widthPx, double heightPx) {
        int columns = widthPx > 0 ? metrics.columnsFor(widthPx) : config.columns();
        int rows = heightPx > 0 ? metrics.rowsFor(heightPx) : config.rows();
        Terminal terminal = Ghostty.open(new TerminalOptions(columns, rows, config.maxScrollback()));
        terminal.setDeviceAttributesProvider(DeviceAttributes::xtermCompatible);
        TerminalPane pane = new TerminalPane(terminal, metrics, config.kittyGraphics(), onContentChange,
                new GhosttyTerminalRenderer(metrics), columns, rows);
        pane.refresh();
        pane.attach(ShellSession.start(config.shell(), config.envOverride(), pane, columns, rows));
        return pane;
    }

    private void attach(ShellSession session) {
        this.session = session;
        terminal.setPtyWriter(bytes -> {
            ShellSession current = this.session;
            if (current != null) {
                current.send(bytes);
            }
        });
        session.startReading(this);
    }

    public void write(String text) {
        synchronized (terminal) {
            terminal.write(text);
            refresh();
        }
    }

    public void write(byte[] bytes) {
        synchronized (terminal) {
            terminal.write(bytes);
            refresh();
        }
    }

    public void send(String text) {
        scrollViewportToBottom();
        if (session != null) {
            session.send(text);
        }
    }

    public boolean sendMouse(MouseInput input, MouseEncoderSize size, boolean anyButtonPressed) {
        synchronized (terminal) {
            mouseEncoder.syncFromTerminal(terminal);
            mouseEncoder.setSize(size);
            mouseEncoder.setAnyButtonPressed(anyButtonPressed);
            mouseEncoder.setTrackLastCell(input.action() == MouseAction.MOTION && input.button().isEmpty());

            byte[] encoded = mouseEncoder.encode(input);
            if (encoded.length == 0) {
                return false;
            }

            if (session != null) {
                session.send(encoded);
            }
            return true;
        }
    }

    public void scrollViewport(long rows) {
        synchronized (terminal) {
            terminal.scrollViewport(ScrollViewport.delta(rows));
            refresh();
        }
    }

    private void scrollViewportToBottom() {
        synchronized (terminal) {
            terminal.scrollViewport(ScrollViewport.bottom());
            refresh();
        }
    }

    /**
     * Incremental snapshot: cells are marshalled only for rows that changed since the last
     * frame (global dirty == PARTIAL), reused across calls for the same content version.
     * Snapshotting is deferred here rather than done in refresh(), so a burst of writes
     * between two frames collapses into a single snapshot.
     */
    @Override
    public RenderStateSnapshot snapshot() {
        return takeSnapshot(false);
    }

    /**
     * Full snapshot with every row's cells populated. Used where the whole pane is redrawn
     * regardless of dirty state (the kitty-graphics path).
     */
    @Override
    public RenderStateSnapshot snapshotFull() {
        return takeSnapshot(true);
    }

    private RenderStateSnapshot takeSnapshot(boolean full) {
        synchronized (terminal) {
            long version = contentVersion.get();
            if (full) {
                renderState.update(terminal);
                cachedSnapshot = renderState.snapshot();
                renderState.resetDirty();
                snapshotVersion = version;
            } else if (snapshotVersion != version) {
                renderState.update(terminal);
                cachedSnapshot = renderState.snapshotIncremental();
                renderState.resetDirty();
                snapshotVersion = version;
            }
            return cachedSnapshot;
        }
    }

    public String scrollbackText() {
        synchronized (terminal) {
            return terminal.text();
        }
    }

    /** This pane's own content revision, bumped on every change (see {@link #refresh()}). */
    public long contentVersion() {
        return contentVersion.get();
    }

    @Override
    public boolean kittyEnabled() {
        return kittyEnabled;
    }

    @Override
    public Optional<KittyGraphics> kittyGraphics() {
        synchronized (terminal) {
            return terminal.kittyGraphics();
        }
    }

    @Override
    public double x() {
        return x;
    }

    @Override
    public double y() {
        return y;
    }

    @Override
    public double width() {
        return width;
    }

    @Override
    public double height() {
        return height;
    }

    public void bounds(double x, double y, double width, double height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** Set the clip region applied on the next paints (see {@link RenderTarget#clip()}). */
    public void setClip(Shape clip) {
        this.clip = clip;
    }

    @Override
    public Shape clip() {
        return clip;
    }

    /** Recompute the ghostty grid from the current bounds and the shared cell metrics. */
    public void fitToBounds() {
        int columns = metrics.columnsFor(width);
        int rows = metrics.rowsFor(height);
        resize(columns, rows, (int) Math.round(metrics.cellWidth()), (int) Math.round(metrics.lineHeight()));
    }

    private void resize(int columns, int rows, int pixelWidth, int pixelHeight) {
        if (columns <= 0 || rows <= 0 || pixelWidth <= 0 || pixelHeight <= 0) {
            return;
        }
        if (this.columns == columns && this.rows == rows && this.pixelWidth == pixelWidth && this.pixelHeight == pixelHeight) {
            return;
        }

        synchronized (terminal) {
            terminal.resize(columns, rows, pixelWidth, pixelHeight);
            if (session != null) {
                session.resize(columns, rows);
            }
            this.columns = columns;
            this.rows = rows;
            this.pixelWidth = pixelWidth;
            this.pixelHeight = pixelHeight;
            refresh();
        }
    }

    private void refresh() {
        // Mark this pane's content dirty (the snapshot is computed lazily in the paint path,
        // so a burst of writes collapses into one snapshot per frame) and tell the owning tab
        // one of its panes changed.
        contentVersion.incrementAndGet();
        onContentChange.run();
    }

    /** Paint the whole pane; see {@link TerminalRenderer#paintFull}. */
    public long paintFull(GraphicsContext gc, boolean active) {
        renderer.paintFull(gc, this, active);
        return snapshotVersion;
    }

    /** Repaint what changed; see {@link TerminalRenderer#paintIncremental}. */
    public long paintIncremental(GraphicsContext gc, boolean active) {
        renderer.paintIncremental(gc, this, active);
        return snapshotVersion;
    }

    @Override
    public void close() {
        if (session != null) {
            session.close();
            session = null;
        }
        mouseEncoder.close();
        renderState.close();
        terminal.close();
    }
}

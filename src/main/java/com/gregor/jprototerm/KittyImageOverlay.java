package com.gregor.jprototerm;

import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders kitty graphics images as retained scene-graph nodes layered over the {@link Compositor}
 * canvas, instead of compositing them onto the canvas. Each pane gets a {@link Group} clipped to
 * that pane's region (the same clip {@code Shape} the canvas renderer uses), and each visible
 * image placement is an {@link ImageView} inside it, reused across frames so an unchanged image
 * costs nothing to redraw.
 *
 * <p>The overlay {@link #node()} is mouse-transparent and sits above the canvas in the window's
 * {@code StackPane}; its children use scene coordinates, which line up with the canvas because
 * both fill the same root.
 */
final class KittyImageOverlay {
    private final Pane root = new Pane();
    private final Map<TerminalPane, PaneOverlay> overlays = new HashMap<>();

    KittyImageOverlay() {
        // Input belongs to the canvas underneath; the overlay only shows pixels.
        root.setMouseTransparent(true);
        root.setManaged(false);
    }

    Node node() {
        return root;
    }

    /**
     * Full reconcile to {@code panes} (bottom-to-top): drop overlays for panes that went away,
     * refresh each surviving/added pane's images and clip, and order the per-pane groups to match
     * the pane z-order. Called on layout frames, after the panes have painted.
     */
    void sync(List<TerminalPane> panes) {
        Iterator<Map.Entry<TerminalPane, PaneOverlay>> it = overlays.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<TerminalPane, PaneOverlay> entry = it.next();
            if (!panes.contains(entry.getKey())) {
                root.getChildren().remove(entry.getValue().group);
                it.remove();
            }
        }
        for (TerminalPane pane : panes) {
            updatePane(pane);
        }
        List<Node> ordered = new ArrayList<>(panes.size());
        for (TerminalPane pane : panes) {
            ordered.add(overlays.get(pane).group);
        }
        if (!root.getChildren().equals(ordered)) {
            root.getChildren().setAll(ordered);
        }
    }

    /**
     * Refresh one pane's images and clip (called on content frames for each repainted pane).
     * Creates the pane's group if this is the first time it has shown an image.
     */
    void updatePane(TerminalPane pane) {
        List<KittyImageNode> images = pane.kittyImages();
        PaneOverlay overlay = overlays.get(pane);
        if (overlay == null) {
            if (images.isEmpty()) {
                return;
            }
            overlay = new PaneOverlay();
            overlays.put(pane, overlay);
            root.getChildren().add(overlay.group);
        }
        overlay.group.setClip(clipFor(pane));
        reconcile(overlay, images);
    }

    private static void reconcile(PaneOverlay overlay, List<KittyImageNode> images) {
        Set<Long> seen = new HashSet<>();
        for (KittyImageNode node : images) {
            long key = node.key();
            seen.add(key);
            ImageView view = overlay.views.get(key);
            if (view == null) {
                view = new ImageView();
                view.setManaged(false);
                view.setSmooth(true);
                view.setPreserveRatio(false);
                overlay.views.put(key, view);
                overlay.group.getChildren().add(view);
            }
            apply(view, node);
        }
        if (overlay.views.size() == seen.size()) {
            return;
        }
        Iterator<Map.Entry<Long, ImageView>> it = overlay.views.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, ImageView> entry = it.next();
            if (!seen.contains(entry.getKey())) {
                overlay.group.getChildren().remove(entry.getValue());
                it.remove();
            }
        }
    }

    private static void apply(ImageView view, KittyImageNode node) {
        if (view.getImage() != node.image()) {
            view.setImage(node.image());
        }
        view.setViewport(new Rectangle2D(node.sourceX(), node.sourceY(), node.sourceWidth(), node.sourceHeight()));
        view.setFitWidth(node.width());
        view.setFitHeight(node.height());
        view.setLayoutX(node.x());
        view.setLayoutY(node.y());
    }

    // The pane's occlusion clip when one is set (rect minus covering panes), else the pane's
    // plain bounds so an image can't spill outside its pane. Matches Tab's pixel snapping.
    private static Shape clipFor(TerminalPane pane) {
        Shape clip = pane.clip();
        if (clip != null) {
            return clip;
        }
        return new Rectangle(Math.round(pane.x()), Math.round(pane.y()), pane.width(), pane.height());
    }

    private static final class PaneOverlay {
        private final Group group = new Group();
        private final Map<Long, ImageView> views = new HashMap<>();

        private PaneOverlay() {
            group.setManaged(false);
        }
    }
}

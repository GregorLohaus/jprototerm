package com.gregor.jprototerm;

import javafx.scene.image.Image;

/**
 * A single kitty image to display, produced by the renderer and consumed by {@link
 * KittyImageOverlay}. Images are not painted onto the canvas; each becomes a retained
 * {@code ImageView} node positioned over the pane. The {@code source*} fields are the region of
 * {@link #image()} to show (in image pixels); the {@code x/y/width/height} are where to put it,
 * in scene coordinates (the same space the pane's clip {@code Shape} lives in).
 *
 * <p>{@code imageId}+{@code placementId} identify the placement so the overlay can reuse the
 * same node across frames instead of recreating it.
 */
record KittyImageNode(
        long imageId,
        long placementId,
        Image image,
        double sourceX,
        double sourceY,
        double sourceWidth,
        double sourceHeight,
        double x,
        double y,
        double width,
        double height
) {
    /** Stable per-pane key for node reuse. Packs the two u32 ids without collision. */
    long key() {
        return (imageId << 32) | (placementId & 0xffffffffL);
    }
}

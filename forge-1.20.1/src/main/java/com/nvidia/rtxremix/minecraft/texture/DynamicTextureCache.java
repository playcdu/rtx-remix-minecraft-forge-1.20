/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.client.renderer.texture.AbstractTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Pixel cache for dynamic textures (maps, player skins, etc.) that are not
 * accessible through the resource manager.
 *
 * <p>Textures are captured via two mixin paths:
 * <ul>
 *   <li>{@code NativeImageBackedTextureMixin} and {@code NativeImageUploadMixin}, for
 *       {@code DynamicTexture} instances (capes, maps, etc.): the texture's image is
 *       tagged as an {@link UploadSource} and every GL upload from it, whole or a
 *       sub-rectangle, is copied into the snapshot.</li>
 *   <li>{@code PlayerSkinTextureMixin}, for {@code HttpTexture} instances
 *       (player skins downloaded from Mojang CDN)</li>
 * </ul>
 *
 * <p>Sub-rectangle uploads matter on Forge: ImmediatelyFast's map atlas generation packs
 * every map into a shared 4096-pixel {@code DynamicTexture} and uploads each changed map
 * as a 128-pixel window of the atlas image, never through {@code DynamicTexture.upload}.
 *
 * <p>In both cases, {@code TextureManagerMixin} intercepts {@code register()}
 * to record the texture-to-identifier mapping in {@link #TEXTURE_IDS}, and the type-specific
 * mixin captures pixel data at upload time via {@link #captureFromUpload}.
 *
 * <p>Font glyph pages are served here too but never snapshotted: Minecraft assembles a
 * page on the GPU one glyph at a time and {@link GlyphPageMirror} follows along, so
 * {@link #get} reads the page's mirror live. A page belongs to the font, not to a world,
 * so {@link #clear} leaves the pages alone.
 *
 * <p>A snapshot follows its texture rather than the world: {@link #clear} drops the
 * pixels of textures the game has itself released and keeps live registrations,
 * and the capacity bound evicts released textures only, oldest first, so it is a soft
 * bound while live textures alone exceed it. Vanilla memoises a downloaded skin for the
 * whole client session and uploads it exactly once, so a snapshot dropped for any reason
 * other than its texture's release would never be captured again and the player would
 * render with the default skin until the game restarts. {@link #TEXTURE_IDS} holds its
 * keys weakly. Image-backed textures also count as released once their image is null:
 * vanilla can close a map texture without removing it from the texture manager.
 *
 * <p>Versions are monotonic for the life of the process. Consumers keep the version they
 * last applied and compare it against the current one, so a counter reset would let a
 * snapshot that outlived a clear mask the upload that follows it.
 */
public final class DynamicTextureCache {

    public record Pixels(byte[] data, int width, int height, long version) {}

    private static final int DEFAULT_MAX_CACHE_ENTRIES = 256;
    private static final int MAX_CACHE_ENTRIES = Math.max(32,
            Integer.getInteger("rtxremix.dynamicTextureCache.maxEntries", DEFAULT_MAX_CACHE_ENTRIES));

    /** Completed pixel cache keyed by texture identifier, in access order so pruning is LRU. */
    private static final Map<ResourceLocation, Pixels> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true));

    /** Glyph pages by texture id; see the class comment for why they sit apart from {@link #CACHE}. */
    private static final Map<ResourceLocation, GlyphPageMirror> GLYPH_PAGES =
            Collections.synchronizedMap(new HashMap<>());

    /**
     * Maps a texture object to its identifier so that upload-time mixins
     * ({@code NativeImageBackedTextureMixin}, {@code PlayerSkinTextureMixin})
     * can look up the id when {@code upload()} fires.  Uses identity semantics
     * since {@code AbstractTexture} does not override {@code equals}/{@code hashCode}.
     */
    private static final Map<AbstractTexture, ResourceLocation> TEXTURE_IDS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static long nextVersion = 1L;

    /**
     * Implemented on {@code NativeImage} by {@code NativeImageUploadMixin}: the tracked
     * texture an image backs, so its uploads can be mirrored with one field read.
     */
    public interface UploadSource {
        ResourceLocation rtxremix$uploadTarget();
        void rtxremix$setUploadTarget(ResourceLocation id);
    }

    // ---- Called from TextureManagerMixin ----

    /**
     * Records the identifier for a texture about to be registered.
     * If the image is already available, pixels are captured immediately.
     * The mapping stays live so later uploads from mutable textures can replace
     * the cached pixels.
     */
    public static void onRegisterTexture(ResourceLocation id, AbstractTexture texture, NativeImage imageOrNull) {
        TEXTURE_IDS.put(texture, id);
        if (imageOrNull != null && isCapturable(imageOrNull)) {
            // Image available now (e.g. cape textures, non-async paths).
            store(id, extractPixels(imageOrNull));
            if (texture instanceof DynamicTexture) {
                tagUploadSource(imageOrNull, id);
            }
        }
    }

    /** Registers the mirror of a glyph page under the id {@code FontSet} gave it. */
    public static void onRegisterGlyphPage(ResourceLocation id, GlyphPageMirror page) {
        GLYPH_PAGES.put(id, page);
    }

    // ---- Called from upload-time mixins ----

    /**
     * Captures pixel data at upload time, where {@code image} is guaranteed non-null.
     * Called from {@code NativeImageBackedTextureMixin} and {@code PlayerSkinTextureMixin}.
     *
     * @return true if the texture was registered and pixels were stored
     */
    public static boolean captureFromUpload(AbstractTexture texture, NativeImage image) {
        ResourceLocation id = TEXTURE_IDS.get(texture);
        if (id == null || !isCapturable(image)) return false;
        store(id, extractPixels(image));
        return true;
    }

    /**
     * Marks {@code image} as the source of a tracked {@code DynamicTexture}'s uploads, so
     * {@link #captureUploadedRegion} follows it. Called from
     * {@code NativeImageBackedTextureMixin} before every whole-texture upload, which also
     * picks up an image swapped in by {@code setPixels}; does nothing for an untracked texture.
     */
    public static void trackUploadSource(AbstractTexture texture, NativeImage image) {
        ResourceLocation id = TEXTURE_IDS.get(texture);
        if (id != null) {
            tagUploadSource(image, id);
        }
    }

    /**
     * Mirrors one level-0 GL upload from a tagged image into the snapshot: the window at
     * ({@code srcX}, {@code srcY}) of the image lands at ({@code dstX}, {@code dstY}), as
     * {@code glTexSubImage2D} writes it. Without a snapshot of the image's size the whole
     * image is snapshotted instead, which is also what a whole-texture upload amounts to.
     * Called from {@code NativeImageUploadMixin} on the render thread.
     */
    public static void captureUploadedRegion(NativeImage image, ResourceLocation id,
                                             int dstX, int dstY, int srcX, int srcY,
                                             int width, int height) {
        if (!isCapturable(image)) {
            return;
        }
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        Pixels snapshot = CACHE.get(id);
        boolean wholeImage = dstX == 0 && dstY == 0 && srcX == 0 && srcY == 0
                && width == imageWidth && height == imageHeight;
        if (wholeImage || snapshot == null
                || snapshot.width() != imageWidth || snapshot.height() != imageHeight) {
            store(id, extractPixels(image));
            return;
        }
        int columns = Math.min(width, Math.min(imageWidth - srcX, imageWidth - dstX));
        int rows = Math.min(height, Math.min(imageHeight - srcY, imageHeight - dstY));
        if (srcX < 0 || srcY < 0 || dstX < 0 || dstY < 0 || columns <= 0 || rows <= 0) {
            return;
        }
        // In place: every reader copies the array when the version moves, on this thread.
        byte[] out = snapshot.data();
        for (int row = 0; row < rows; row++) {
            int idx = ((dstY + row) * imageWidth + dstX) * 4;
            for (int column = 0; column < columns; column++, idx += 4) {
                int abgr = image.getPixelRGBA(srcX + column, srcY + row);
                out[idx]     = (byte)  (abgr         & 0xFF);
                out[idx + 1] = (byte) ((abgr >>  8)  & 0xFF);
                out[idx + 2] = (byte) ((abgr >> 16)  & 0xFF);
                out[idx + 3] = (byte) ((abgr >> 24)  & 0xFF);
            }
        }
        store(id, new Pixels(out, imageWidth, imageHeight, allocateVersion()));
    }

    /**
     * Only RGBA images are snapshotted: {@code getPixelRGBA} throws for any other format,
     * and a throw here would escape into the texture manager's registration.
     */
    private static boolean isCapturable(NativeImage image) {
        return image.format() == NativeImage.Format.RGBA;
    }

    private static void tagUploadSource(NativeImage image, ResourceLocation id) {
        // NativeImage is final, so the mixin-added interface is only visible through Object.
        if ((Object) image instanceof UploadSource source) {
            source.rtxremix$setUploadTarget(id);
        }
    }

    /** The number of snapshots above which released textures are pruned. */
    static int capacity() {
        return MAX_CACHE_ENTRIES;
    }

    // ---- Public accessors ----

    /** The current pixels of a snapshotted texture or a glyph page, or null for anything else. */
    public static Pixels get(ResourceLocation id) {
        Pixels snapshot = CACHE.get(id);
        if (snapshot != null) {
            return snapshot;
        }
        GlyphPageMirror page = GLYPH_PAGES.get(id);
        return page == null
                ? null
                : new Pixels(page.data(), page.width(), page.height(), page.version());
    }

    public static boolean isGlyphPage(ResourceLocation id) {
        return GLYPH_PAGES.containsKey(id);
    }

    /** Drops the snapshots of released textures; live registrations and glyph pages stay. */
    public static void clear() {
        Set<ResourceLocation> liveIds = liveTextureIds();
        synchronized (CACHE) {
            CACHE.keySet().retainAll(liveIds);
        }
    }

    // ---- Internal helpers ----

    /**
     * Stores a snapshot and, above capacity, prunes the snapshots of released
     * textures oldest first. A live texture's snapshot is never pruned: its
     * pixels cannot be captured again.
     */
    private static void store(ResourceLocation id, Pixels pixels) {
        CACHE.put(id, pixels);
        if (CACHE.size() <= MAX_CACHE_ENTRIES) {
            return;
        }
        Set<ResourceLocation> liveIds = liveTextureIds();
        synchronized (CACHE) {
            Iterator<ResourceLocation> snapshots = CACHE.keySet().iterator();
            while (CACHE.size() > MAX_CACHE_ENTRIES && snapshots.hasNext()) {
                if (!liveIds.contains(snapshots.next())) {
                    snapshots.remove();
                }
            }
        }
    }

    private static Set<ResourceLocation> liveTextureIds() {
        Set<ResourceLocation> liveIds = new HashSet<>();
        synchronized (TEXTURE_IDS) {
            for (var entry : TEXTURE_IDS.entrySet()) {
                if (!(entry.getKey() instanceof DynamicTexture texture)
                        || texture.getPixels() != null) {
                    liveIds.add(entry.getValue());
                }
            }
        }
        return liveIds;
    }

    private static Pixels extractPixels(NativeImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] out = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = image.getPixelRGBA(x, y);
                int idx = (y * w + x) * 4;
                out[idx]     = (byte)  (abgr         & 0xFF);
                out[idx + 1] = (byte) ((abgr >>  8)  & 0xFF);
                out[idx + 2] = (byte) ((abgr >> 16)  & 0xFF);
                out[idx + 3] = (byte) ((abgr >> 24)  & 0xFF);
            }
        }
        return new Pixels(out, w, h, allocateVersion());
    }

    private static synchronized long allocateVersion() {
        return nextVersion++;
    }

    private DynamicTextureCache() {}
}

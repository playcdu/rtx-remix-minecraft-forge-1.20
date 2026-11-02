/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.world.WorldRendererExtended;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Keeps {@link WorldRendererTerrainAccess} on {@code LevelRenderer}, where the mod's
 * callers look for it, while Embeddium owns the sections: every method forwards to the
 * section manager of this renderer's {@code SodiumWorldRenderer}
 * ({@link EmbeddiumRenderSectionManagerMixin}). With no world loaded there is no
 * section manager, and the methods report nothing built and queue nothing.
 *
 * <p>Adds interface methods only; no injection into {@code LevelRenderer}, so it
 * cannot collide with Embeddium's overwrites of that class.
 */
@Mixin(LevelRenderer.class)
public abstract class EmbeddiumLevelRendererMixin implements WorldRendererTerrainAccess {

    @Override
    public LongSet rtxremix$builtSectionPositions() {
        WorldRendererTerrainAccess sections = rtxremix$sections();
        return sections != null ? sections.rtxremix$builtSectionPositions() : new LongOpenHashSet();
    }

    @Override
    public LongSet rtxremix$queueSectionsForRemix(LongSet sections) {
        WorldRendererTerrainAccess manager = rtxremix$sections();
        return manager != null ? manager.rtxremix$queueSectionsForRemix(sections) : LongSets.emptySet();
    }

    @Override
    public void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz) {
        WorldRendererTerrainAccess manager = rtxremix$sections();
        if (manager != null) {
            manager.rtxremix$scheduleResidentSectionRebuild(cx, sy, cz);
        }
    }

    /** The current section manager, which {@link EmbeddiumRenderSectionManagerMixin} extends. */
    @Unique
    private WorldRendererTerrainAccess rtxremix$sections() {
        SodiumWorldRenderer worldRenderer = ((WorldRendererExtended) this).sodium$getWorldRenderer();
        if (worldRenderer == null) {
            return null;
        }
        RenderSectionManager manager =
                ((EmbeddiumSodiumWorldRendererAccessor) worldRenderer).rtxremix$getRenderSectionManager();
        return manager != null ? (WorldRendererTerrainAccess) manager : null;
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * Terrain rebuild scheduling owned by the world renderer.
 *
 * <p>Embeddium owns section building, so {@code LevelRenderer} implements this by
 * delegating to the {@code RenderSectionManager} of its {@code SodiumWorldRenderer}
 * ({@code EmbeddiumLevelRendererMixin}, {@code EmbeddiumRenderSectionManagerMixin}).
 * Render thread only.
 */
public interface WorldRendererTerrainAccess {

    /**
     * The sections Embeddium currently holds built data for, as packed
     * {@code SectionPos} longs. This is the terrain the player has seen:
     * Embeddium builds a section when its occlusion walk reaches it, so the set is
     * the frustum-driven subset of the loaded area. Take it before a
     * {@code LevelRenderer.allChanged()}, which replaces Embeddium's section
     * manager and forgets it.
     */
    LongSet rtxremix$builtSectionPositions();

    /**
     * Queues the loaded, non-empty, lit sections in {@code sections} for Remix
     * capture, nearest the camera first. Embeddium otherwise rebuilds only the
     * sections its occlusion walk reaches, so a section behind the camera would
     * stay missing from the Remix scene until the player looked at it.
     *
     * @return the positions actually queued, as packed {@code SectionPos} longs;
     *         a subset of {@code sections}, never a view of it
     */
    LongSet rtxremix$queueSectionsForRemix(LongSet sections);

    /**
     * Schedules a rebuild of a section if Embeddium still holds it, whether or
     * not it is in view. A section Embeddium no longer holds is skipped: it is
     * built again when its column becomes ready.
     */
    void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz);
}

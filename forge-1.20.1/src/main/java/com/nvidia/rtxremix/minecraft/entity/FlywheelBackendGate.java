/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;

/**
 * Decides whether Flywheel may draw while Remix presents the world.
 *
 * <p>Create 6 draws kinetic blocks, belts, contraptions and trains through Flywheel
 * 1.0, which instances them straight onto the GPU. Remix sees only what reaches it
 * through the capture, so instanced geometry would simply be absent from the
 * path-traced scene. {@code FlywheelBackendMixin} therefore makes Flywheel's
 * {@code BackendManagerImpl.isBackendOn()} report false while this returns true.
 * Every Flywheel consumer, and Create through
 * {@code VisualizationManager.supportsVisualization(level)}, then takes the path it
 * takes on hardware with no supported backend: block entities stay with their
 * {@code BlockEntityRenderer}s, entities with their {@code EntityRenderer}s, and both
 * draw into whichever buffer source they are given, which the capture already handles.
 *
 * <p>The answer follows the master RTX flag rather than the entity or block-entity
 * sub-toggles: while Remix presents, instanced geometry is invisible whether or not
 * the capture of its fallback is enabled. A renderer that never initialised leaves
 * vanilla presenting, so Flywheel stays on.
 *
 * <p>Switching takes effect cleanly because {@code RtxRemixMod.setRtxEnabled} flips the
 * flag before it schedules {@code LevelRenderer.allChanged()}: Flywheel's own
 * {@code allChanged} hook resets the level's visualization manager (deleting its
 * instances) and Embeddium rebuilds every section, so block entities a section build
 * had handed to Flywheel come back to the block-entity list, or, switching off, are
 * handed back to a fresh manager that also re-adds every loaded entity.
 *
 * <p>Read from Flywheel on any thread, including Embeddium's section builders, so it
 * reads only a volatile flag and two plain references, and allocates nothing.
 */
public final class FlywheelBackendGate {

    private FlywheelBackendGate() {}

    /** Whether Remix is presenting the world, so Flywheel's backend must read as off. */
    public static boolean suppressFlywheel() {
        return RtxRemixMod.isRemixPresenting();
    }
}

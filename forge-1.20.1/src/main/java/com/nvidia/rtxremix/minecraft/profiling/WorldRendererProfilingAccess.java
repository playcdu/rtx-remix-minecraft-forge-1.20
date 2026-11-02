/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.profiling;

/**
 * Lets the mod close {@code WorldRendererMixin}'s open profiler phase from
 * outside the mixin, so a world-render event handler (on Forge, a
 * {@code RenderLevelStageEvent} stage such as {@code AFTER_LEVEL}) that takes
 * over the rest of the frame is not billed to whichever vanilla phase happened
 * to be open. The name is kept from the Fabric version so callers line up.
 * Render thread only.
 */
public interface WorldRendererProfilingAccess {
    void rtxremix$closeProfilerPhaseForFabricEvent();
}

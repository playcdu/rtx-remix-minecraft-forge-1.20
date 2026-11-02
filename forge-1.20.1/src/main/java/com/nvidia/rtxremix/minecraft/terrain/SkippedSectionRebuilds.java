/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import java.util.concurrent.atomic.AtomicInteger;

/** Counts section rebuild requests the mod declined because Remix would not use them. */
public final class SkippedSectionRebuilds {
    private static final AtomicInteger LIGHT_ONLY = new AtomicInteger();

    private SkippedSectionRebuilds() {
    }

    /** Records a rebuild requested only because the client light engine changed a section's light. */
    public static void recordLightOnly() {
        recordLightOnly(1);
    }

    /** Records {@code count} such rebuilds dropped together. */
    public static void recordLightOnly(int count) {
        LIGHT_ONLY.addAndGet(count);
    }

    /** Returns and resets the light-only count, for the per-frame terrain counters. */
    public static int drainLightOnly() {
        return LIGHT_ONLY.getAndSet(0);
    }
}

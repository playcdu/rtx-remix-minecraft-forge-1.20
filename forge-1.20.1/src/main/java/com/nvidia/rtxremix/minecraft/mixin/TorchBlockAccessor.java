/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the particle a torch block spawns from its display tick.
 *
 * <p>1.20.1 types the field as {@link ParticleOptions} (1.21.1 narrowed it to
 * {@code SimpleParticleType}); an accessor must return the field's exact type,
 * so callers that need a {@code SimpleParticleType} test for it.
 */
@Mixin(TorchBlock.class)
public interface TorchBlockAccessor {
    @Accessor("flameParticle")
    ParticleOptions getParticle();
}

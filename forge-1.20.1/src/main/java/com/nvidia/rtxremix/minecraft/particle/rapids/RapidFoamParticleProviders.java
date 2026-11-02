/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle.rapids;

import net.minecraftforge.client.event.RegisterParticleProvidersEvent;

/**
 * Binds the rapids particle types to their client particle factories.
 *
 * <p>Kept apart from {@link RapidFoamParticles}, which dedicated servers load to register
 * the types: the factories and the provider event exist only on the client.
 */
public final class RapidFoamParticleProviders {
    private RapidFoamParticleProviders() {}

    /** Binds each type to its provider and the sprites its particle JSON lists. */
    public static void register(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(RapidFoamParticles.RAPID_FOAM.get(), RapidFoamParticle.Factory::new);
        event.registerSpriteSet(RapidFoamParticles.RAPID_FOAM_DOT.get(), RapidFoamDotParticle.Factory::new);
        event.registerSpriteSet(RapidFoamParticles.RAPID_MIST.get(), RapidMistParticle.Factory::new);
    }
}

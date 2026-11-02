/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle.rapids;

import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The three client-spawned particle types of the water rapids effect.
 *
 * <p>Registered on both sides through a {@link DeferredRegister} so a dedicated server
 * with the mod knows them too. Forge syncs the particle-type registry during the
 * multiplayer handshake, though, and builds the client's registry for that session from
 * the server's entries alone: on a server without the mod these types are absent for
 * the whole connection, their {@link RegistryObject}s report not present, and
 * {@link #isAvailable()} turns the spawner off until the client leaves. Nothing is ever
 * sent over the network for them, so the server never sees one either way.
 */
public final class RapidFoamParticles {
    private static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, RtxRemixCommonMod.MOD_ID);

    public static final RegistryObject<SimpleParticleType> RAPID_FOAM =
            PARTICLE_TYPES.register("rapid_foam", () -> new SimpleParticleType(false));
    public static final RegistryObject<SimpleParticleType> RAPID_FOAM_DOT =
            PARTICLE_TYPES.register("rapid_foam_dot", () -> new SimpleParticleType(false));
    public static final RegistryObject<SimpleParticleType> RAPID_MIST =
            PARTICLE_TYPES.register("rapid_mist", () -> new SimpleParticleType(false));

    private RapidFoamParticles() {}

    /** Adds the types to the mod event bus; called on both sides. */
    public static void register(IEventBus modBus) {
        PARTICLE_TYPES.register(modBus);
    }

    /** Client only: binds each type to its provider and the sprites its particle JSON lists. */
    public static void registerProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(RAPID_FOAM.get(), RapidFoamParticle.Factory::new);
        event.registerSpriteSet(RAPID_FOAM_DOT.get(), RapidFoamDotParticle.Factory::new);
        event.registerSpriteSet(RAPID_MIST.get(), RapidMistParticle.Factory::new);
    }

    /**
     * Whether all three types are in the active registry. False for the length of a
     * connection to a server that does not have this mod; see the class comment.
     */
    public static boolean isAvailable() {
        return RAPID_FOAM.isPresent() && RAPID_FOAM_DOT.isPresent() && RAPID_MIST.isPresent();
    }
}

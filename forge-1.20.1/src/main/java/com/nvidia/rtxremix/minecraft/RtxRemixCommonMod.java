/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconUpdatePayload;
import com.nvidia.rtxremix.minecraft.block.SpotLightBlock;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenHandler;
import com.nvidia.rtxremix.minecraft.particle.rapids.RapidFoamParticles;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Common (both-sides) registration.
 *
 * <p>The renderer itself is client-only and lives in {@link RtxRemixMod}. This
 * class exists solely for content that must exist on the logical server too:
 * the beacon blocks, their menu and update message, and the particle types.
 *
 * <p><b>Why this is separate:</b> blocks live in a <i>synced</i> registry. A
 * block registered only on the client desynchronises from a server that has
 * never heard of it, so it cannot be placed or persisted in multiplayer.
 * Forge registers through {@code DeferredRegister}s on the mod event bus, and
 * it syncs the particle type registry too, so the particle types that the
 * Fabric build kept client-only register here as well.
 *
 * <p>Everything reachable from here must avoid client-only classes: this runs
 * on dedicated servers, where {@code net.minecraft.client.*} does not exist.
 * {@link RtxRemixForgeMod} calls {@link #register(IEventBus)} on both sides,
 * before it wires the client entrypoint on the client dist.
 */
public final class RtxRemixCommonMod {

    /**
     * The mod id, and the namespace of every identifier this mod registers.
     *
     * <p>Owned by this class rather than by {@link RtxRemixMod}: block
     * registration runs on dedicated servers, where the client entrypoint
     * cannot be loaded, so a constant that path depends on must not live on a
     * client-only class.
     */
    public static final String MOD_ID = "rtxremix";

    private RtxRemixCommonMod() {}

    /** Registers the common content's deferred registers and network channel. */
    public static void register(IEventBus modBus) {
        SpotLightBlock.registerAll(modBus);
        CopperBeaconScreenHandler.register(modBus);
        CopperBeaconUpdatePayload.register();
        RapidFoamParticles.register(modBus);
    }
}

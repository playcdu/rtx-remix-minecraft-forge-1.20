/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * The Forge entry class, constructed once by FML on both sides.
 *
 * <p>The common content registers first, on every dist. The client entrypoint,
 * {@link RtxRemixMod}, is wired only on the client dist, and only from inside the
 * branch below, so a dedicated server never loads it or any
 * {@code net.minecraft.client} class it references.
 */
@Mod(RtxRemixCommonMod.MOD_ID)
public final class RtxRemixForgeMod {

    public RtxRemixForgeMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        RtxRemixCommonMod.register(modBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientEntry.bootstrap(modBus);
        }
    }

    /**
     * Holds the only reference to the client entrypoint, so resolving it waits until
     * the client branch actually runs.
     */
    private static final class ClientEntry {
        private ClientEntry() {}

        static void bootstrap(IEventBus modBus) {
            RtxRemixMod.bootstrap(modBus);
        }
    }
}

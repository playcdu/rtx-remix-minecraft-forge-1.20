/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.IdMappingEvent;

/**
 * Re-indexes the block light rule catalog whenever Forge renumbers registry
 * IDs: on joining a server (the client adopts the server's numbering), on
 * leaving it, and when a single-player save is loaded. Fabric's build had no
 * equivalent because its registry sync rarely changed block-state numbering
 * for a vanilla-ordered server.
 */
@Mod.EventBusSubscriber(
        modid = RtxRemixCommonMod.MOD_ID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BlockLightRuleRemapListener {

    private BlockLightRuleRemapListener() {}

    @SubscribeEvent
    public static void onIdMapping(IdMappingEvent event) {
        BlockLightRuleManager.reindexStateIds();
    }
}

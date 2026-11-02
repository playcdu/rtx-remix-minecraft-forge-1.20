/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;

/**
 * Adds the beacon block items to vanilla's "Functional Blocks" creative tab,
 * where torches and lanterns live. Forge's counterpart of Fabric's
 * {@code ItemGroupEvents.modifyEntriesEvent(FUNCTIONAL_BLOCKS)}.
 */
@Mod.EventBusSubscriber(modid = RtxRemixCommonMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SpotLightCreativeTab {

    private SpotLightCreativeTab() {}

    @SubscribeEvent
    public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            return;
        }
        for (RegistryObject<Item> item : SpotLightBlock.creativeTabItems()) {
            event.accept(item);
        }
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Additional data needed to bind the client-side handler to its block entity.
 *
 * <p>Forge carries it as the extra buffer {@code NetworkHooks.openScreen} writes
 * after the open-screen packet; {@link CopperBeaconScreenHandler#TYPE}'s
 * {@code IForgeMenuType} factory reads it back with {@link #decode}.
 */
public record CopperBeaconScreenData(BlockPos pos) {

    public static CopperBeaconScreenData decode(FriendlyByteBuf buffer) {
        return new CopperBeaconScreenData(buffer.readBlockPos());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
    }
}

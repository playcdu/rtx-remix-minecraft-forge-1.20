/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Reports the client columns a chunk-cache resize drops as unloaded.
 *
 * <p>Forge posts {@code ChunkEvent.Unload} for a client column only from
 * {@code ClientChunkCache.drop}. When the server shrinks the view radius,
 * {@code updateViewRadius} copies only the columns inside the new storage range
 * and drops the rest without an event, and the forget packets that follow find
 * nothing left to drop. Fabric API reported those columns through
 * {@code ClientChunkEvents.CHUNK_UNLOAD}; without this hook their Remix terrain
 * would stay published until the world changed.
 */
@Mixin(ClientChunkCache.class)
public abstract class CoreClientChunkCacheMixin {

    @ModifyExpressionValue(
            method = "updateViewRadius",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;inRange(II)Z"))
    private boolean rtxremix$unloadColumnOutsideNewRange(boolean inRange, @Local LevelChunk chunk) {
        if (!inRange) {
            RtxRemixMod.onClientChunkUnloaded(chunk.getPos());
        }
        return inRange;
    }
}

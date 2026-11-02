/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets walls connect to a Copper Beacon mounted on their side and raise their
 * post under one mounted on top. Common side: wall shapes are computed on the
 * server too.
 *
 * <p>Tests the block's class rather than {@code CopperBeaconBlock.INSTANCE.get()}:
 * on a server without this mod Forge drops the block from the synced registry
 * and the registry object becomes empty, and walls are shaped constantly.
 */
@Mixin(WallBlock.class)
abstract class WallBlockMixin {

    @Inject(method = "connectsTo", at = @At("HEAD"), cancellable = true)
    private void rtxremix$connectToCopperBeacon(
            BlockState state,
            boolean faceFullSquare,
            Direction side,
            CallbackInfoReturnable<Boolean> callback) {
        if (state.getBlock() instanceof CopperBeaconBlock) {
            callback.setReturnValue(
                    state.getValue(CopperBeaconBlock.MOUNT) == side.getOpposite());
        }
    }

    @Inject(method = "shouldRaisePost", at = @At("HEAD"), cancellable = true)
    private void rtxremix$connectPostToCopperBeacon(
            BlockState state,
            BlockState aboveState,
            VoxelShape aboveShape,
            CallbackInfoReturnable<Boolean> callback) {
        if (aboveState.getBlock() instanceof CopperBeaconBlock) {
            callback.setReturnValue(
                    aboveState.getValue(CopperBeaconBlock.MOUNT) == Direction.UP);
        }
    }
}

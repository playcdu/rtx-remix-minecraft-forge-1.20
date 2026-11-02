/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.nvidia.rtxremix.minecraft.entity.FlywheelBackendGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Turns Flywheel's backend off while Remix presents the world; see
 * {@link FlywheelBackendGate} for why and for how a mid-session switch takes effect.
 *
 * <p>Flywheel 1.0 ships inside Create's jar and is not a compile dependency, so the
 * target is named by string, {@code @Pseudo} lets the mixin be skipped when Flywheel
 * is absent, and nothing is remapped: Flywheel's own names are not Minecraft's.
 * {@code isBackendOn()} is the one predicate behind {@code BackendManager.isBackendOn()},
 * {@code VisualizationManager.supportsVisualization(level)} and, through it,
 * {@code VisualizationManager.get(level)}, so overriding it reaches every consumer.
 * A return-value modifier rather than a cancellable injection: the method is queried
 * per block entity and entity every frame, and a cancellable callback would allocate a
 * {@code CallbackInfoReturnable} on each call. {@code require = 0} so a Flywheel update
 * that renames the method costs Create's visibility in Remix, not the game.
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.impl.BackendManagerImpl", remap = false)
public abstract class FlywheelBackendMixin {

    @ModifyReturnValue(method = "isBackendOn()Z", at = @At("RETURN"), remap = false, require = 0)
    private static boolean rtxremix$offWhileRemixPresents(boolean backendOn) {
        return backendOn && !FlywheelBackendGate.suppressFlywheel();
    }
}

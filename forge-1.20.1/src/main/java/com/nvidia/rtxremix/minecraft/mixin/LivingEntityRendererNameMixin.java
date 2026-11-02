/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.entity.PlayerNameText;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The upside-down name check only reads the string; see {@link PlayerNameText}. A
 * {@code WrapOperation} rather than a {@code Redirect}, so another mod's wrap or
 * redirect of the same call still composes.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererNameMixin {
    @WrapOperation(
            method = "isEntityUpsideDown(Lnet/minecraft/world/entity/LivingEntity;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getName()Lnet/minecraft/network/chat/Component;"))
    private static Component rtxremix$cachedName(LivingEntity entity, Operation<Component> original) {
        return entity instanceof PlayerNameText cached ? cached.rtxremix$nameText() : original.call(entity);
    }
}

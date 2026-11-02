/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.entity.PlayerNameText;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.layers.Deadmau5EarsLayer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The ears check only reads the string; see {@link PlayerNameText}. A
 * {@code WrapOperation} rather than a {@code Redirect}, so another mod's wrap or
 * redirect of the same call still composes.
 */
@Mixin(Deadmau5EarsLayer.class)
public abstract class Deadmau5FeatureRendererMixin {
    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;getName()Lnet/minecraft/network/chat/Component;"))
    private Component rtxremix$cachedName(AbstractClientPlayer player, Operation<Component> original) {
        return ((PlayerNameText) player).rtxremix$nameText();
    }
}

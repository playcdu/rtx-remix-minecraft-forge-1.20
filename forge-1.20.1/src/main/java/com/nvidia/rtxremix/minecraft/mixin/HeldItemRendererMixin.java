/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Tells the capture who is holding the item being rendered, and for the
 * first-person view which vanilla hand it is in.
 *
 * <p>Every hand-held render, first person and every entity's hands alike, passes
 * through {@code ItemInHandRenderer.renderItem(LivingEntity, ...)}, so the holder
 * recorded here is what lets the item renderer's hook decide whether a held torch is
 * burning. The first-person hand comes from {@code renderArmWithItem}.
 *
 * <p>Both are wrapped whole rather than bracketed by HEAD and RETURN injections:
 * several mods in the target pack cancel these methods early (Botania, Scholar and
 * Windswept {@code renderItem} at HEAD; Species, Moonlight, Ribbits and Exposure
 * {@code renderArmWithItem} at HEAD or part-way), and a cancelled call never reaches a
 * RETURN injection, which would leave a stale holder or hand on the context for the
 * rest of the capture. {@code @WrapMethod} wraps the body with those injections in it.
 */
@Mixin(ItemInHandRenderer.class)
public class HeldItemRendererMixin {

    @WrapMethod(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    private void rtxremix$trackHeldItemHolder(LivingEntity entity, ItemStack stack, ItemDisplayContext mode,
                                              boolean leftHanded, PoseStack matrices,
                                              MultiBufferSource vertexConsumers, int light,
                                              Operation<Void> original) {
        EntityRenderContext context = EntityRenderContext.get();
        if (!context.isRemixPass()) {
            original.call(entity, stack, mode, leftHanded, matrices, vertexConsumers, light);
            return;
        }
        context.setHeldItemHolder(entity);
        try {
            original.call(entity, stack, mode, leftHanded, matrices, vertexConsumers, light);
        } finally {
            context.clearHeldItemHolder();
        }
    }

    @WrapMethod(method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FF"
            + "Lnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;F"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    private void rtxremix$trackFirstPersonHand(
            AbstractClientPlayer player,
            float tickDelta,
            float pitch,
            InteractionHand hand,
            float swingProgress,
            ItemStack item,
            float equipProgress,
            PoseStack matrices,
            MultiBufferSource vertexConsumers,
            int light,
            Operation<Void> original) {
        EntityRenderContext context = EntityRenderContext.get();
        if (!context.isRemixPass()) {
            original.call(player, tickDelta, pitch, hand, swingProgress, item, equipProgress,
                    matrices, vertexConsumers, light);
            return;
        }
        context.setActiveHand(hand);
        try {
            original.call(player, tickDelta, pitch, hand, swingProgress, item, equipProgress,
                    matrices, vertexConsumers, light);
        } finally {
            context.clearActiveHand();
        }
    }
}

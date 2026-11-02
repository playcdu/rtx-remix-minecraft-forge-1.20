/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.HeldItemEffectsRenderer;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the terrain layer of the block item being rendered, and adds the lit
 * element of a hand-held item.
 *
 * <p>Minecraft routes ordinary opaque items through its translucent item layer,
 * and clear glass through its cutout one, so the render layer alone cannot tell
 * a pane of stained glass from an apple, or clear glass from a torch. The
 * {@code ItemStack} can, and this is the last point at which it is in scope
 * before the geometry reaches capture.
 *
 * <p>Targets Forge's patched {@code ItemRenderer.render}, whose display transform
 * goes through {@code ForgeHooksClient.handleCameraTransforms} and whose baked model
 * may draw in several passes and render types, one {@code renderModelLists} call each.
 */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin {

    private static final String rtxremix$RENDER =
            "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Z"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II"
            + "Lnet/minecraft/client/resources/model/BakedModel;)V";

    @Inject(method = rtxremix$RENDER, at = @At("HEAD"))
    private void rtxremix$beginItem(ItemStack stack, ItemDisplayContext mode, boolean leftHanded,
                                    PoseStack matrices, MultiBufferSource vertexConsumers,
                                    int light, int overlay, BakedModel model, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) {
            context.setBlockItemLayer(rtxremix$blockLayer(stack));
        }
    }

    /**
     * Adds the item's lit element right after its baked model, while the matrix
     * stack still holds the item's block-space pose. Running here rather than in
     * a hand-specific renderer covers every hand-held render with one hook.
     *
     * <p>Forge draws a multi-pass or multi-layer model with one
     * {@code renderModelLists} per pass and render type, so the shared flag keeps
     * the element to the first, as 1.21's single call did. {@code model} is read
     * from its local at this point, so it is the model the display transform was
     * taken from, after Forge's {@code handleCameraTransforms}.
     */
    @Inject(
        method = rtxremix$RENDER,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderModelLists(Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/item/ItemStack;IILcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V",
            shift = At.Shift.AFTER
        )
    )
    private void rtxremix$renderHeldFlame(ItemStack stack, ItemDisplayContext mode, boolean leftHanded,
                                          PoseStack matrices, MultiBufferSource vertexConsumers,
                                          int light, int overlay, BakedModel model, CallbackInfo ci,
                                          @Share("rtxremix$heldFlameDone") LocalBooleanRef heldFlameDone) {
        if (heldFlameDone.get()) return;
        heldFlameDone.set(true);
        HeldItemEffectsRenderer.render(stack, mode, matrices, vertexConsumers, model);
    }

    @Inject(method = rtxremix$RENDER, at = @At("RETURN"))
    private void rtxremix$endItem(ItemStack stack, ItemDisplayContext mode, boolean leftHanded,
                                  PoseStack matrices, MultiBufferSource vertexConsumers,
                                  int light, int overlay, BakedModel model, CallbackInfo ci) {
        EntityRenderContext context = EntityRenderContext.get();
        if (context.isRemixPass()) {
            context.setBlockItemLayer(EntityRenderContext.NO_BLOCK_ITEM);
        }
    }

    /**
     * Asks the question terrain asks of a placed block, through the same
     * classifier, so a held block is classified exactly as its placed
     * counterpart is.
     */
    private static int rtxremix$blockLayer(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                ? BlockTextureManager.layerType(
                        BlockQuadEmitter.classifyBlockLayer(blockItem.getBlock().defaultBlockState()))
                : EntityRenderContext.NO_BLOCK_ITEM;
    }
}

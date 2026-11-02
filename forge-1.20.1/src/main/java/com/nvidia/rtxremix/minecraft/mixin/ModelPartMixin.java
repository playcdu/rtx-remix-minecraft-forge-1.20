/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.entity.RecyclingMatrixStack;
import com.nvidia.rtxremix.minecraft.entity.RemixCapturingConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SpriteCoordinateExpander;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.List;


/**
 * Routes vanilla model-part rendering through the capture's allocation-free paths.
 *
 * <p>{@code translateAndRotate} on a {@link RecyclingMatrixStack} applies the part
 * transform through the stack's pooled quaternion. The math is vanilla's, read from the
 * same position, rotation and scale fields, so a renderer that sets a pivot directly, as
 * the banner does for its flag, is honoured. The entity pass and the block-entity pass
 * both render on such a stack. Embeddium {@code @Overwrite}s {@code translateAndRotate};
 * this HEAD injection lands in its body, so the two coexist and ours short-circuits only
 * on the capture's own stack.
 *
 * <p>The private {@code compile} on a single-cuboid part borrows the capturing consumer's
 * singleton iterator instead of allocating vanilla's. The consumer is found directly
 * or behind the {@link SpriteCoordinateExpander} that sprite-atlas models draw
 * through, the same one-step look-through {@code CuboidMixin} applies. Sprite-wrapped
 * parts therefore reuse the iterator too; a wrapper around anything else is left to
 * vanilla. Embeddium's HEAD injection on the same method takes over only for a
 * {@code VertexBufferWriter} consumer, which the capturing consumer is not, and a
 * {@code WrapOperation} composes with any other mod's wrap of the same call.
 */
@Mixin(ModelPart.class)
public abstract class ModelPartMixin {

    @Shadow public float x;
    @Shadow public float y;
    @Shadow public float z;
    @Shadow public float xRot;
    @Shadow public float yRot;
    @Shadow public float zRot;
    @Shadow public float xScale;
    @Shadow public float yScale;
    @Shadow public float zScale;

    @Inject(
            method = "translateAndRotate(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void rtxremix$applyPooledRotation(PoseStack matrices, CallbackInfo ci) {
        if (!(matrices instanceof RecyclingMatrixStack recyclingMatrices)) {
            return;
        }

        recyclingMatrices.applyModelPartTransform(
                x, y, z,
                xRot, yRot, zRot,
                xScale, yScale, zScale);
        ci.cancel();
    }

    @WrapOperation(
            method = "compile(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    private Iterator<ModelPart.Cube> rtxremix$reuseSingletonCuboidIterator(
            List<ModelPart.Cube> cuboids,
            Operation<Iterator<ModelPart.Cube>> original,
            PoseStack.Pose entry,
            VertexConsumer vertexConsumer,
            int light,
            int overlay,
            float red,
            float green,
            float blue,
            float alpha) {
        VertexConsumer target = vertexConsumer instanceof SpriteCoordinateExpander wrapped
                ? wrapped.delegate
                : vertexConsumer;
        // Local reads first: the cuboid count and list type reject group and
        // multi-cuboid parts before the capture check pays for its thread-local lookup.
        if (target instanceof RemixCapturingConsumer consumer
                && cuboids.size() == 1
                && cuboids instanceof ImmutableList<?>
                && consumer.isRemixCaptureActive()) {
            Iterator<ModelPart.Cube> iterator =
                    consumer.acquireSingletonCuboidIterator(cuboids.get(0));
            if (iterator != null) {
                return iterator;
            }
        }
        return original.call(cuboids);
    }
}

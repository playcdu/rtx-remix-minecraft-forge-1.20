/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.RemixCapturingConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SpriteCoordinateExpander;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@code ModelPart.Cube.compile()} so a cuboid reaches the Remix
 * capture as the immutable object it is rather than as expanded vertices.
 *
 * <p>When the active {@link VertexConsumer} is a {@link RemixCapturingConsumer}
 * inside a Remix entity pass, directly or behind a {@link SpriteCoordinateExpander},
 * the cuboid's polygons, sprite, folded tint and pose are handed to the consumer, which
 * registers the pivot in the {@link EntityRenderContext} and records a rigid part
 * keyed by the cuboid's identity; the vanilla vertex write is cancelled.
 *
 * <p>Embeddium's fast path ({@code ModelPart.compile} HEAD, cancellable) only takes
 * over when the consumer is a {@code VertexBufferWriter}; the capturing consumer is not
 * one, so its cubes still reach this method. A mod cube class that overrides
 * {@code compile} without calling it (Entity Model Features' custom cubes) falls back to
 * the consumer's transformed bulk-vertex path instead.
 *
 * <p>Priority 1100 so this HEAD injection lands after other mods' default-priority HEAD
 * argument rewrites (Shoulder Surfing fades the camera entity through the alpha
 * argument) and the captured tint is the one vanilla would have drawn.
 */
@Mixin(value = ModelPart.Cube.class, priority = 1100)
public class CuboidMixin {

    @Shadow
    @Final
    private ModelPart.Polygon[] polygons;

    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void rtxremix$onRenderCuboid(PoseStack.Pose entry, VertexConsumer vertexConsumer,
                                          int light, int overlay,
                                          float red, float green, float blue, float alpha,
                                          CallbackInfo ci) {
        // Sprite-atlas models (chests, beds, banners, shields, pots) draw through a
        // wrapper whose only behaviour is remapping UVs into the sprite's rectangle.
        // The capture reproduces that remap when it rebuilds the cuboid, so the
        // wrapper is looked through and the sprite travels with the part: one cuboid
        // is baked once and painted with many sprites, so the sprite is part of the
        // part's identity. A wrapper around anything else, such as the glint union
        // ItemRenderer builds, is left to vanilla.
        VertexConsumer target = vertexConsumer;
        TextureAtlasSprite sprite = null;
        if (target instanceof SpriteCoordinateExpander wrapped) {
            sprite = wrapped.sprite;
            target = wrapped.delegate;
        }
        if (!(target instanceof RemixCapturingConsumer consumer)) {
            return;
        }

        EntityRenderContext ctx = EntityRenderContext.get();
        if (!ctx.isRemixPass()) {
            return;
        }

        // The consumer owns pivot registration and the part record so it can tag
        // the part with the cuboid it came from. That tag has to be exact: submit
        // keys the mesh by this cuboid's identity, and a part wrongly attributed
        // would reuse another cuboid's geometry.
        // Folded before capture: the tint is an instance property downstream, so a
        // damage or priming flash is a per-instance update, never a new mesh.
        // 1.20.1 passes the tint as four floats where 1.21 passed packed ARGB.
        int color = rtxremix$channel(alpha) << 24
                | rtxremix$channel(red) << 16
                | rtxremix$channel(green) << 8
                | rtxremix$channel(blue);
        consumer.acceptCuboid(this.polygons,
                RemixCapturingConsumer.applyEntityOverlay(color, overlay),
                entry.pose(),
                sprite);

        ci.cancel();
    }

    /**
     * One tint channel as a byte. Rounded rather than truncated, so a tint a renderer
     * unpacked from ARGB as {@code c / 255f}, the copper beacon's lens or a dyed sheep's
     * wool, packs back to the byte it came from.
     */
    private static int rtxremix$channel(float value) {
        return Math.max(0, Math.min(255, (int) (value * 255.0F + 0.5F)));
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.light.HeldLightState;
import com.nvidia.rtxremix.minecraft.particle.ParticleTint;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.mixin.ParticleManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Draws the lit effects of a hand-held item into the Remix capture: its
 * {@link HeldItemFlame} and the {@link HeldItemSmoke} above it.
 *
 * <p>Runs from the item renderer once the item's block-space pose is on the
 * matrix stack, so one hook serves the first-person view model, the local
 * player's hidden shadow model, other players and any mob or armor stand with a
 * torch in hand. The quads go through the same capture provider as the item,
 * on an emissive layer over the particle atlas, and the submitter gives that
 * layer the particle pipeline's own material, so a held flame and a placed
 * flame are lit by the same numbers.
 *
 * <p>The facing is 1.21's camera rotation, which these poses were built for: a quad
 * so rotated has its front (+Z) toward the viewer and stands upright in view space.
 * 1.20.1's {@code Camera.rotation()} is that rotation without its final half turn
 * about +Y (1.21 builds it as {@code rotationYXZ(PI - yaw, -pitch, 0)} looking down
 * -Z, 1.20.1 as {@code rotationYXZ(-yaw, pitch, 0)} looking down +Z), so it is
 * restored here; see {@link #facing}. Entity and block-entity capture spaces are
 * world-aligned, so that rotation applies as it is, and world up is +Y there, the
 * direction smoke rises. The first-person hand is not: 1.20.1's
 * {@code GameRenderer.renderItemInHand} resets the hand stack to identity, so the hand
 * is drawn in view space under the model-view (1.21 rooted it at the inverse camera
 * rotation and carried that rotation in the model-view instead). A quad facing the
 * viewer is unrotated in view space, so the first-person facing is only the inverse
 * of whatever the model-view holds, identity during vanilla's hand render.
 *
 * <p>Render thread only: the scratch objects are shared across calls.
 */
public final class HeldItemEffectsRenderer {

    private static final RenderType EFFECT_LAYER =
            RenderType.entityTranslucentEmissive(TextureAtlas.LOCATION_PARTICLES);
    private static final float TICKS_PER_SECOND = 20.0f;
    private static final int OPAQUE = 255;
    private static final int WHITE = 255;
    private static final int SMOKE_ALPHA = Math.round(HeldItemSmoke.OPACITY * 255.0f);
    private static final int SMOKE_GREY = Math.round(ParticleTint.SOOT_GREY * 255.0f);

    /** The half turn about +Y between 1.20.1's camera rotation and 1.21's. */
    private static final Quaternionfc CAMERA_HALF_TURN = new Quaternionf().rotationY((float) Math.PI);

    private static final Matrix4f scratchPose = new Matrix4f();
    private static final Matrix4f scratchModelView = new Matrix4f();
    private static final Quaternionf scratchFacing = new Quaternionf();
    private static final Vector3f scratchAnchor = new Vector3f();
    private static final Vector3f scratchScale = new Vector3f();
    private static final HeldItemSmoke.Puff[] scratchPuffs = new HeldItemSmoke.Puff[HeldItemSmoke.SLOTS];

    static {
        for (int i = 0; i < scratchPuffs.length; i++) scratchPuffs[i] = new HeldItemSmoke.Puff();
    }

    private HeldItemEffectsRenderer() {}

    /**
     * Emits the flame and smoke quads for {@code stack} if the Remix capture is
     * active, {@code mode} is a hand, the item carries a lit element, and that
     * element is burning in its holder's hand, see {@link HeldLightState}.
     *
     * @param matrices  the item renderer's stack, with the item's block-space
     *                  pose on top; left as found
     * @param model     the item's baked model, whose display transform for
     *                  {@code mode} is part of that pose
     */
    public static void render(ItemStack stack, ItemDisplayContext mode,
                              PoseStack matrices, MultiBufferSource consumers,
                              BakedModel model) {
        EntityRenderContext context = EntityRenderContext.get();
        if (!context.isRemixPass() || !HeldItemFlame.isHandMode(mode)) return;
        SimpleParticleType flameParticle = HeldItemFlame.particleFor(stack);
        if (flameParticle == null) return;
        // Null outside a hand: an item_display posed with a hand transform reaches
        // this hook with no ItemInHandRenderer above it. Nothing in HeldLightState
        // can put out a prop, so it burns.
        LivingEntity holder = context.getHeldItemHolder();
        if (holder != null && !HeldLightState.isLit(holder, stack)) return;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        SpriteSet flameSprites = spriteProvider(client, flameParticle);
        SpriteSet smokeSprites = spriteProvider(client, ParticleTypes.SMOKE);
        if (flameSprites == null || smokeSprites == null) return;

        Quaternionfc facing = facing(mode, client);
        Quaternionfc frame = HeldItemFlame.riseFrame(mode, facing);
        double ticks = client.level.getGameTime() + client.getPartialTick();
        float phase = mode.ordinal();
        float cameraOffset = HeldItemFlame.cameraOffset(mode);
        // Null when the capture does not know where the eye is; the poses then stay at depth 1.
        Vector3fc eye = context.getEye();
        float depthFraction = HeldItemFlame.depthFraction(mode);
        Matrix4f itemPose = matrices.last().pose();
        // A hand draws its item scaled down (item/generated: 0.68 in first person,
        // 0.55 in third). The flame and smoke are world-sized, so the poses divide
        // that scale back out.
        float displayScale = model.getTransforms().getTransform(mode).scale.x();
        VertexConsumer buffer = consumers.getBuffer(EFFECT_LAYER);

        matrices.pushPose();
        PoseStack.Pose entry = matrices.last();
        entry.normal().rotation(facing);

        double seconds = ticks / TICKS_PER_SECOND;
        HeldItemFlame.composePose(itemPose, displayScale, facing, frame,
                HeldItemFlame.flicker(seconds, phase), HeldItemFlame.bob(seconds, phase),
                cameraOffset, eye, depthFraction, scratchAnchor, scratchScale, scratchPose);
        entry.pose().set(scratchPose);
        // A freshly spawned particle of the type shows the provider's first sprite.
        emitQuad(buffer, entry, flameSprites.get(0, 1), WHITE, OPAQUE);

        // Every slot is emitted, dormant ones included, so part ordinals stay
        // fixed: the retained instance behind each quad keeps its identity.
        if (HeldItemSmoke.showsFor(mode, RtxRemixMod.isFirstPersonSmokeEnabled())) {
            HeldItemSmoke.evaluate(ticks, phase, scratchPuffs);
            for (HeldItemSmoke.Puff puff : scratchPuffs) {
                HeldItemSmoke.composePose(itemPose, displayScale, facing, frame, puff, cameraOffset, eye,
                        depthFraction, scratchAnchor, scratchScale, scratchPose);
                entry.pose().set(scratchPose);
                emitQuad(buffer, entry, smokeSprites.get(puff.age, puff.maxAge),
                        SMOKE_GREY, SMOKE_ALPHA);
            }
        }
        matrices.popPose();
    }

    /**
     * The rotation that turns a quad in its XY plane, front +Z, toward the viewer in the
     * capture space of {@code mode}; see the class comment. Allocation-free; the result
     * lives in scratch and is valid until the next call.
     */
    private static Quaternionfc facing(ItemDisplayContext mode, Minecraft client) {
        if (mode.firstPerson()) {
            return scratchModelView.set(RenderSystem.getModelViewMatrix()).invert()
                    .getNormalizedRotation(scratchFacing);
        }
        return scratchFacing.set(client.gameRenderer.getMainCamera().rotation())
                .mul(CAMERA_HALF_TURN);
    }

    private static SpriteSet spriteProvider(Minecraft client, ParticleType<?> particle) {
        Object provider = ((ParticleManagerAccessor) client.particleEngine)
                .getSpriteAwareFactories()
                .get(BuiltInRegistries.PARTICLE_TYPE.getKey(particle));
        return provider instanceof SpriteSet spriteProvider ? spriteProvider : null;
    }

    /**
     * A unit quad in the entry's XY plane, front toward +Z, sprite top at +Y.
     * The vertex colour multiplies the sprite, alpha included, under the
     * vertex-colour blend state the submitter gives these parts.
     */
    private static void emitQuad(VertexConsumer buffer, PoseStack.Pose entry, TextureAtlasSprite sprite,
                                 int grey, int alpha) {
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        vertex(buffer, entry, -1.0f, -1.0f, u0, v1, grey, alpha);
        vertex(buffer, entry, 1.0f, -1.0f, u1, v1, grey, alpha);
        vertex(buffer, entry, 1.0f, 1.0f, u1, v0, grey, alpha);
        vertex(buffer, entry, -1.0f, 1.0f, u0, v0, grey, alpha);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose entry,
                               float x, float y, float u, float v, int grey, int alpha) {
        buffer.vertex(entry.pose(), x, y, 0.0f)
                .color(grey, grey, grey, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(entry.normal(), 0.0f, 0.0f, 1.0f)
                .endVertex();
    }
}

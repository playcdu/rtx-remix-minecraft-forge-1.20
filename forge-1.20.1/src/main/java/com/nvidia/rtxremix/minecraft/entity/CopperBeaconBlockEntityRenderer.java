/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconPose;
import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.resources.model.Material;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;

/** Renders the independently rotating yoke and lamp head above the static mount. */
public final class CopperBeaconBlockEntityRenderer
        implements BlockEntityRenderer<CopperBeaconBlockEntity> {

    public static final ModelLayerLocation BODY_MODEL_LAYER = new ModelLayerLocation(
            new ResourceLocation(RtxRemixCommonMod.MOD_ID, "copper_beacon"), "body");
    public static final ModelLayerLocation LENS_MODEL_LAYER = new ModelLayerLocation(
            new ResourceLocation(RtxRemixCommonMod.MOD_ID, "copper_beacon"), "lens");
    private static final Material COPPER_TEXTURE = new Material(
            InventoryMenu.BLOCK_ATLAS_TEXTURE,
            new ResourceLocation("block/copper_block"));
    static final ResourceLocation LENS_SPRITE_ID =
            new ResourceLocation(RtxRemixCommonMod.MOD_ID, "block/copper_beacon_lens");
    static final ResourceLocation UNLIT_LENS_SPRITE_ID =
            new ResourceLocation(RtxRemixCommonMod.MOD_ID, "block/copper_beacon_lens_unlit");
    private static final Material LENS_TEXTURE = new Material(
            InventoryMenu.BLOCK_ATLAS_TEXTURE,
            LENS_SPRITE_ID);
    private static final Material UNLIT_LENS_TEXTURE = new Material(
            InventoryMenu.BLOCK_ATLAS_TEXTURE,
            UNLIT_LENS_SPRITE_ID);

    private final ModelPart yoke;
    private final ModelPart head;
    private final ModelPart lens;
    private final CopperBeaconPose.Pose pose = new CopperBeaconPose.Pose();
    private final VerticalUvStripConsumer copperUvConsumer = new VerticalUvStripConsumer();

    public CopperBeaconBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        ModelPart bodyRoot = context.bakeLayer(BODY_MODEL_LAYER);
        yoke = bodyRoot.getChild("yoke");
        head = yoke.getChild("head");
        lens = context.bakeLayer(LENS_MODEL_LAYER).getChild("lens");
    }

    public static LayerDefinition getBodyTexturedModelData() {
        MeshDefinition modelData = new MeshDefinition();
        PartDefinition root = modelData.getRoot();
        PartDefinition yokeData = root.addOrReplaceChild(
                "yoke",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-3.0f, -6.0f, -1.0f, 3.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(0.0f, -6.0f, -1.0f, 3.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(-5.0f, -5.0f, -1.0f, 2.0f, 8.0f, 3.0f)
                        .texOffs(0, 0).addBox(3.0f, -5.0f, -1.0f, 2.0f, 8.0f, 3.0f),
                PartPose.offset(8.0f, 10.0f, 8.0f));
        yokeData.addOrReplaceChild(
                "head",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, -3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, 3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, 3.0f, 4.0f, 8.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -3.0f, -2.0f, 1.0f, 6.0f, 5.0f)
                        .texOffs(0, 0).addBox(3.0f, -3.0f, -2.0f, 1.0f, 6.0f, 5.0f)
                        .texOffs(0, 0).addBox(-3.0f, -4.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(-3.0f, 3.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(0.0f, 3.0f, -2.0f, 3.0f, 1.0f, 5.0f)
                        .texOffs(0, 0).addBox(-6.0f, -1.0f, -1.0f, 2.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(4.0f, -1.0f, -1.0f, 2.0f, 2.0f, 3.0f)
                        .texOffs(0, 0).addBox(-4.0f, -4.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, -4.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, 3.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(0.0f, 3.0f, -4.0f, 4.0f, 1.0f, 1.0f)
                        .texOffs(0, 0).addBox(-4.0f, -3.0f, -4.0f, 1.0f, 6.0f, 1.0f)
                        .texOffs(0, 0).addBox(3.0f, -3.0f, -4.0f, 1.0f, 6.0f, 1.0f),
                PartPose.NONE);
        return LayerDefinition.of(modelData, 16, 16);
    }

    public static LayerDefinition getLensTexturedModelData() {
        MeshDefinition modelData = new MeshDefinition();
        PartDefinition root = modelData.getRoot();
        root.addOrReplaceChild(
                "lens",
                CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-3.0f, -3.0f, -4.01f, 6.0f, 6.0f, 0.0f),
                PartPose.offset(8.0f, 10.0f, 8.0f));
        return LayerDefinition.of(modelData, 6, 6);
    }

    @Override
    public void render(
            CopperBeaconBlockEntity beacon,
            float tickDelta,
            PoseStack matrices,
            MultiBufferSource vertexConsumers,
            int light,
            int overlay) {
        BlockState state = beacon.getCachedState();
        Direction up = state.get(CopperBeaconBlock.MOUNT);
        Direction forward = state.get(BlockStateProperties.FACING);
        double animationTick = CopperBeaconAnimationClock.sample(
                beacon.getWorld(), tickDelta);

        matrices.pushPose();
        matrices.translate(0.5, 0.5, 0.5);
        CopperBeaconPose.evaluate(
                up,
                forward,
                beacon.yawAngleAt(animationTick),
                beacon.pitchAngleAt(animationTick),
                pose);
        matrices.rotateAround(pose.mountingRotation());
        matrices.translate(-0.5, -0.5, -0.5);

        applyArticulation(yoke, head, lens, pose);

        VertexConsumer copperVertices = copperUvConsumer.wrap(
                COPPER_TEXTURE.buffer(vertexConsumers, RenderType::entitySolid));
        yoke.render(matrices, copperVertices, light, overlay);
        Material lensTexture =
                lensTexture(beacon.isActive(), beacon.getIntensityMultiplier());
        VertexConsumer lensVertices =
                lensTexture.buffer(vertexConsumers, RenderType::entitySolid);
        lens.render(matrices, lensVertices, light, overlay, beacon.getLensTint());
        matrices.popPose();
    }

    static Quaternionf mountingRotation(Direction upDirection, Direction forwardDirection) {
        return CopperBeaconPose.mountingRotation(
                upDirection, forwardDirection, new Quaternionf());
    }

    static void applyArticulation(
            ModelPart yoke,
            ModelPart head,
            ModelPart lens,
            CopperBeaconPose.Pose pose) {
        yoke.yaw = pose.modelYawRadians();
        yoke.pitch = 0.0f;
        head.yaw = 0.0f;
        head.pitch = pose.modelPitchRadians();
        lens.yaw = pose.modelYawRadians();
        lens.pitch = pose.modelPitchRadians();
    }

    static Material lensTexture(boolean active, float intensityMultiplier) {
        return active && intensityMultiplier > 0.0f
                ? LENS_TEXTURE
                : UNLIT_LENS_TEXTURE;
    }

    static float copperTextureV(float v) {
        return 0.25f + v * 0.5f;
    }

    /**
     * Keeps resource-pack details along the texture edges while excluding all
     * four corners, where stylized copper textures commonly place rivets.
     */
    private static final class VerticalUvStripConsumer implements VertexConsumer {
        private VertexConsumer delegate;

        VertexConsumer wrap(VertexConsumer delegate) {
            this.delegate = delegate;
            return this;
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            delegate.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            delegate.color(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            delegate.uv(u, copperTextureV(v));
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            delegate.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            delegate.light(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            delegate.normal(x, y, z);
            return this;
        }
    }
}

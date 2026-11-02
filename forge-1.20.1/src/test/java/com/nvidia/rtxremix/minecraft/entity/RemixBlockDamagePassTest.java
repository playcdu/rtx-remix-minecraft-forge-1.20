/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.MinecraftTestBootstrap;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the two pure parts of the block-breaking pass.
 *
 * <p>The owner tokens decide whether a crack keeps its retained scene instance from
 * one frame to the next: vanilla allocates a fresh {@code BlockPos} per broken block
 * per frame and the submitter's registry keys by owner identity, so a position must map
 * to one token while it is rendered and lose it once it is not.
 *
 * <p>The projector replaces vanilla's {@code SheetedDecalTextureGenerator}, so its texture
 * coordinates are pinned against the vanilla consumer on an identity pose for every
 * face, and it is pinned to deliver them before {@code endVertex}, which is where
 * {@link RemixCapturingConsumer} closes a vertex.
 */
class RemixBlockDamagePassTest {

    private static final int OVERLAY = 0;
    private static final int LIGHT = 0;
    private static final float UV_TOLERANCE = 1.0e-6f;

    /**
     * Vanilla's crack consumer and the projector both pick a face through Forge's
     * {@code getNearestStable}, which reads Forge's client config.
     */
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.forgeClientConfigDefaults();
    }

    @AfterEach
    void clearRenderContext() {
        EntityRenderContext.get().reset();
    }

    // ---- owner tokens ----

    @Test
    void positionAbsentForAFrameLosesItsOwner() {
        RemixBlockDamagePass.PositionOwners owners = new RemixBlockDamagePass.PositionOwners();

        Object first = owners.ownerFor(42L);
        owners.endFrame();
        owners.endFrame();
        assertEquals(0, owners.size());

        Object again = owners.ownerFor(42L);
        assertNotSame(first, again);
    }

    @Test
    void onlyUnseenPositionsAreRetired() {
        RemixBlockDamagePass.PositionOwners owners = new RemixBlockDamagePass.PositionOwners();

        Object kept = owners.ownerFor(1L);
        owners.ownerFor(2L);
        owners.endFrame();
        Object seenAgain = owners.ownerFor(1L);
        owners.endFrame();

        assertSame(kept, seenAgain);
        assertEquals(1, owners.size());
    }

    // ---- crack projection ----

    @Test
    void projectionMatchesVanillaOverlayConsumerOnEveryFace() {
        for (Direction face : Direction.values()) {
            Recorder vanillaOut = new Recorder();
            VertexConsumer vanilla = vanillaCrackConsumer(vanillaOut);
            Recorder projectorOut = new Recorder();
            VertexConsumer projector = new RemixBlockDamagePass.CrackProjector().target(projectorOut);

            feedFace(vanilla, face);
            feedFace(projector, face);

            List<Recorder.Vertex> expected = vanillaOut.finish();
            List<Recorder.Vertex> actual = projectorOut.finish();
            assertEquals(4, expected.size(), face.toString());
            assertEquals(expected.size(), actual.size(), face.toString());
            for (int i = 0; i < expected.size(); i++) {
                Recorder.Vertex e = expected.get(i);
                Recorder.Vertex a = actual.get(i);
                String where = face + " vertex " + i;
                assertEquals(e.x(), a.x(), where);
                assertEquals(e.y(), a.y(), where);
                assertEquals(e.z(), a.z(), where);
                assertEquals(e.color(), a.color(), where);
                assertEquals(e.u(), a.u(), UV_TOLERANCE, where);
                assertEquals(e.v(), a.v(), UV_TOLERANCE, where);
                assertEquals(e.nx(), a.nx(), where);
                assertEquals(e.ny(), a.ny(), where);
                assertEquals(e.nz(), a.nz(), where);
            }
        }
    }

    @Test
    void projectedUvIsPresentWhenTheCapturingConsumerClosesTheVertex() {
        EntityRenderContext.get().begin(true);
        Recorder vanillaOut = new Recorder();
        feedFace(vanillaCrackConsumer(vanillaOut), Direction.NORTH);
        List<Recorder.Vertex> expected = vanillaOut.finish();

        RemixCapturingConsumer capture = new RemixCapturingConsumer(null, true);
        feedFace(new RemixBlockDamagePass.CrackProjector().target(capture), Direction.NORTH);
        capture.finishOpenVertex();

        assertEquals(4, capture.getVertexCount());
        assertEquals(1, capture.getPartCount());
        for (int i = 0; i < 4; i++) {
            assertEquals(expected.get(i).u(), capture.getTu()[i], UV_TOLERANCE, "u of vertex " + i);
            assertEquals(expected.get(i).v(), capture.getTv()[i], UV_TOLERANCE, "v of vertex " + i);
        }
    }

    /** Vanilla's crack consumer on an identity pose, as the projector's reference. */
    private static VertexConsumer vanillaCrackConsumer(VertexConsumer delegate) {
        PoseStack.Pose identity = new PoseStack().last();
        return new SheetedDecalTextureGenerator(delegate, identity.pose(), identity.normal(), 1.0F);
    }

    /**
     * Feeds the four corners of one unit-cube face the way {@code VertexConsumer.putBulkData}
     * does: through the element-by-element default, with the block model's own atlas
     * coordinates and a tinted, shaded colour that the crack must not inherit.
     */
    private static void feedFace(VertexConsumer consumer, Direction face) {
        Vector3f n = face.step();
        Vector3f tangent = Math.abs(n.y) > 0.5f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f bitangent = new Vector3f(n).cross(tangent).normalize();
        tangent = new Vector3f(bitangent).cross(n).normalize();
        Vector3f center = new Vector3f(0.5f).add(new Vector3f(n).mul(0.5f));
        int[][] corners = {{-1, -1}, {-1, 1}, {1, 1}, {1, -1}};
        for (int i = 0; i < corners.length; i++) {
            Vector3f p = new Vector3f(center)
                    .add(new Vector3f(tangent).mul(0.5f * corners[i][0]))
                    .add(new Vector3f(bitangent).mul(0.5f * corners[i][1]));
            int shadedTint = 0xFF3080C0 - i;
            consumer.vertex(p.x, p.y, p.z,
                    ((shadedTint >> 16) & 0xFF) / 255.0f, ((shadedTint >> 8) & 0xFF) / 255.0f,
                    (shadedTint & 0xFF) / 255.0f, (shadedTint >>> 24) / 255.0f,
                    0.25f + 0.1f * i, 0.75f, OVERLAY, LIGHT, n.x, n.y, n.z);
        }
    }

    /** Records each vertex's final element values, whatever order they arrive in. */
    private static final class Recorder implements VertexConsumer {
        record Vertex(float x, float y, float z, int color, float u, float v,
                      float nx, float ny, float nz) {}

        private final List<Vertex> vertices = new ArrayList<>();
        private float x, y, z, u, v, nx, ny, nz;
        private int color;

        List<Vertex> finish() {
            return vertices;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            this.x = (float) x; this.y = (float) y; this.z = (float) z;
            return this;
        }

        @Override
        public void endVertex() {
            vertices.add(new Vertex(x, y, z, color, u, v, nx, ny, nz));
            color = 0;
            u = 0; v = 0;
            nx = 0; ny = 0; nz = 0;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            color = (alpha << 24) | (red << 16) | (green << 8) | blue;
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            this.u = u; this.v = v;
            return this;
        }

        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public void defaultColor(int red, int green, int blue, int alpha) { }
        @Override public void unsetDefaultColor() { }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            nx = x; ny = y; nz = z;
            return this;
        }
    }
}

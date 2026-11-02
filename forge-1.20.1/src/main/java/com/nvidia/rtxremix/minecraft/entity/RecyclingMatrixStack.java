/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.mixin.MatrixStackAccessor;
import org.joml.Quaternionf;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Matrix stack dedicated to Remix entity capture.
 *
 * <p>Vanilla allocates an entry, a position matrix, and a normal matrix for
 * every {@link #pushPose()}. Entity models repeat that traversal every frame, so
 * popped entries are retained here and copied back into service on the next
 * push. The pool belongs to this stack and therefore never changes vanilla
 * rendering outside the capture pass. 1.20.1's {@code PoseStack.Pose} has no
 * normalization flag to carry over, so an entry is exactly its two matrices.
 *
 * <p>Embeddium {@code @Overwrite}s {@code PoseStack.pushPose}/{@code popPose} with a
 * cache of its own that only {@code ModelPart.render} enables; these overrides replace
 * both, so that cache stays empty for this stack and the two never share an entry.
 */
public final class RecyclingMatrixStack extends PoseStack {

    private final Deque<Pose> recycledEntries = new ArrayDeque<>();
    private final Quaternionf modelPartRotation = new Quaternionf();

    @Override
    public void pushPose() {
        Deque<Pose> stack = stack();
        Pose previous = stack.getLast();
        Pose next = recycledEntries.pollLast();
        if (next == null) {
            super.pushPose();
            return;
        }

        next.pose().set(previous.pose());
        next.normal().set(previous.normal());
        stack.addLast(next);
    }

    @Override
    public void popPose() {
        recycledEntries.addLast(stack().removeLast());
    }

    /** Restores the identity root after a normal render or renderer exception. */
    void reset() {
        Deque<Pose> stack = stack();
        while (stack.size() > 1) {
            recycledEntries.addLast(stack.removeLast());
        }
        setIdentity();
    }

    /** Applies vanilla's {@code ModelPart.translateAndRotate} transform without allocating a quaternion. */
    public void applyModelPartTransform(float x, float y, float z,
                                        float xRot, float yRot, float zRot,
                                        float xScale, float yScale, float zScale) {
        translate(x / 16.0F, y / 16.0F, z / 16.0F);
        if (xRot != 0.0F || yRot != 0.0F || zRot != 0.0F) {
            mulPose(modelPartRotation.rotationZYX(zRot, yRot, xRot));
        }
        if (xScale != 1.0F || yScale != 1.0F || zScale != 1.0F) {
            scale(xScale, yScale, zScale);
        }
    }

    private Deque<Pose> stack() {
        return ((MatrixStackAccessor) (Object) this).rtxremix$getStack();
    }
}

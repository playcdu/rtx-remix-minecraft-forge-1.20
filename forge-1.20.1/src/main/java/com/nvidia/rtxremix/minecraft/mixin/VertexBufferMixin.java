/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.nvidia.rtxremix.minecraft.gl.DynamicBufferUpload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.ByteBuffer;

/**
 * Routes the {@code glBufferData} re-specification of DYNAMIC buffers through
 * {@link DynamicBufferUpload}. Every other upload keeps the original call.
 *
 * <p>A wrap rather than a redirect: ImmediatelyFast's {@code fast_buffer_upload}
 * redirects the same two calls, and a second redirect of one call is skipped and
 * fails its required injection. A wrap applies on top of that redirect instead, so
 * DYNAMIC uploads take this policy and the rest reach ImmediatelyFast's, which
 * makes the same orphan-only-on-growth decision.
 */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferMixin {
    private static final String rtxremix$GL_BUFFER_DATA =
        "Lcom/mojang/blaze3d/systems/RenderSystem;glBufferData(ILjava/nio/ByteBuffer;I)V";

    private final DynamicBufferUpload.Storage rtxremix$vertexStorage = new DynamicBufferUpload.Storage();
    private final DynamicBufferUpload.Storage rtxremix$indexStorage = new DynamicBufferUpload.Storage();

    @WrapOperation(
        method = "uploadVertexBuffer(Lcom/mojang/blaze3d/vertex/BufferBuilder$DrawState;Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/vertex/VertexFormat;",
        at = @At(value = "INVOKE", target = rtxremix$GL_BUFFER_DATA))
    private void rtxremix$uploadVertexData(int target, ByteBuffer data, int usage, Operation<Void> original) {
        rtxremix$upload(target, data, usage, rtxremix$vertexStorage, original);
    }

    // 1.20.1 has one uploadIndexBuffer; its full descriptor keeps the selector exact
    // against the SRG refmap.
    @WrapOperation(
        method = "uploadIndexBuffer(Lcom/mojang/blaze3d/vertex/BufferBuilder$DrawState;Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/systems/RenderSystem$AutoStorageIndexBuffer;",
        at = @At(value = "INVOKE", target = rtxremix$GL_BUFFER_DATA))
    private void rtxremix$uploadIndexData(int target, ByteBuffer data, int usage, Operation<Void> original) {
        rtxremix$upload(target, data, usage, rtxremix$indexStorage, original);
    }

    private static void rtxremix$upload(
            int target, ByteBuffer data, int usage, DynamicBufferUpload.Storage storage, Operation<Void> original) {
        if (DynamicBufferUpload.handles(usage)) {
            DynamicBufferUpload.upload(target, data, usage, storage);
        } else {
            original.call(target, data, usage);
        }
    }
}

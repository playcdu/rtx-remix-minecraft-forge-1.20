/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Replaces the buffer swap inside {@code RenderSystem.flipFrame} with Remix's
 * present, so Vulkan owns the swapchain whenever the renderer is initialized.
 * Before that, and if initialization fails, the GLFW swap runs unchanged.
 *
 * <p>A wrap rather than a redirect, so another mod wrapping the same swap
 * (Embeddium only redirects the first {@code pollEvents} here) chains with it
 * instead of failing to load.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {

    @WrapOperation(
        method = "flipFrame",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V", remap = false)
    )
    private static void rtxremix$presentThroughRemix(long window, Operation<Void> swapBuffers) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null && renderer.isInitialized()) {
            renderer.presentFrameVk();
        } else {
            swapBuffers.call(window);
        }
    }
}

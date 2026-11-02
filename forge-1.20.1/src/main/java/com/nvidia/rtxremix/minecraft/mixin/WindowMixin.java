/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.Window;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.glfwGetWindowAttrib;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;

/**
 * Raises the OpenGL context the window is created with to what the Remix GL
 * interop needs, and logs the GLFW handle and context version once the window
 * exists.
 *
 * <p>Forge creates the window through {@code ImmediateWindowHandler}. With its
 * early loading window on (the default), the window already exists when this
 * constructor runs: FML's early display creates it, asking for the newest core
 * context first (4.6, then down to 3.2), so the hints below have no effect and
 * the log line reports what the early display obtained. With the early window
 * off, {@code NoVizFallback} creates the window from the hints vanilla sets
 * here, so raising them before that call is what keeps the context at 4.4.
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    @Shadow
    @Final
    private long window;

    /**
     * Runs after the last of vanilla's six context hints (forward compatibility),
     * ahead of {@code ImmediateWindowHandler.setupMinecraftWindow}. That call
     * itself is redirected by Embeddium, so it is not used as the anchor. Not
     * required: on the default early-window path the hints are inert, so a
     * conflicting mod must not be able to stop the game over them.
     */
    @Inject(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V",
            ordinal = 5,
            shift = At.Shift.AFTER,
            remap = false
        ),
        require = 0
    )
    private void rtxremix$requireOpenGl44(CallbackInfo ci) {
        // Core-profile 4.4: the GL interop imports Remix's images and semaphores
        // through EXT_memory_object and EXT_semaphore.
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 4);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
    }

    /** Logs the GLFW window handle and its context version once the window exists. */
    @Inject(
        method = "<init>",
        at = @At("TAIL")
    )
    private void rtxremix$onWindowCreated(CallbackInfo ci) {
        RtxRemixMod.LOGGER.info("Window created with GLFW handle: {} (OpenGL {}.{})",
                window,
                glfwGetWindowAttrib(window, GLFW_CONTEXT_VERSION_MAJOR),
                glfwGetWindowAttrib(window, GLFW_CONTEXT_VERSION_MINOR));
    }
}

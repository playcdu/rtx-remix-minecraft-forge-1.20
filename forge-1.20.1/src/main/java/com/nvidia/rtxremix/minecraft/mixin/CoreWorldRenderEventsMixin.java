/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires the world-render events the Fabric build took from Fabric API's
 * {@code WorldRenderEvents}, at the matching points of
 * {@code LevelRenderer.renderLevel}. Forge has no event at the head or the
 * return of the level render, which is where the frame starts and is marked
 * ready to submit, so those two are required. The rest only move profiling
 * phase boundaries, and are optional so that another mod rewriting the same
 * spot cannot stop the game over them. Block outline capture, and the two
 * phases Forge already marks with {@code RenderLevelStageEvent}, are event
 * listeners in {@link RtxRemixMod}.
 *
 * <p>Embeddium does not overwrite {@code renderLevel}, so these points are
 * vanilla's; the profiler section names they anchor on are vanilla constants.
 */
@Mixin(LevelRenderer.class)
public abstract class CoreWorldRenderEventsMixin {

    /** {@code WorldRenderEvents.START}. */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void rtxremix$worldRenderStart(CallbackInfo ci) {
        RtxRemixMod.onWorldRenderStart();
    }

    /** {@code WorldRenderEvents.AFTER_SETUP}: right after {@code setupRender}. */
    @Inject(
            method = "renderLevel",
            at = @At(value = "CONSTANT", args = "stringValue=compilechunks"),
            require = 0)
    private void rtxremix$worldRenderAfterSetup(CallbackInfo ci) {
        RtxRemixMod.onWorldRenderAfterSetup();
    }

    /** {@code WorldRenderEvents.BEFORE_ENTITIES}: after the cutout layer. */
    @Inject(
            method = "renderLevel",
            at = @At(value = "CONSTANT", args = "stringValue=entities"),
            require = 0)
    private void rtxremix$worldRenderBeforeEntities(CallbackInfo ci) {
        RtxRemixMod.onWorldRenderBeforeEntities();
    }

    /** {@code WorldRenderEvents.AFTER_ENTITIES}: the point Fabric API also anchors on. */
    @Inject(
            method = "renderLevel",
            at = @At(value = "CONSTANT", args = "stringValue=blockentities"),
            require = 0)
    private void rtxremix$worldRenderAfterEntities(CallbackInfo ci) {
        RtxRemixMod.onWorldRenderAfterEntities();
    }

    /**
     * {@code WorldRenderEvents.BEFORE_BLOCK_OUTLINE}: the second read of
     * {@code Minecraft.hitResult}, which starts the outline check. The first is
     * the block-entity dispatcher's {@code prepare} at the top of the method.
     */
    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/Minecraft;hitResult:Lnet/minecraft/world/phys/HitResult;",
                    ordinal = 1),
            require = 0)
    private void rtxremix$worldRenderBeforeBlockOutline(CallbackInfo ci) {
        RtxRemixMod.onWorldRenderBeforeBlockOutline();
    }

    /** {@code WorldRenderEvents.BEFORE_DEBUG_RENDER}. */
    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/debug/DebugRenderer;render("
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD)V"),
            require = 0)
    private void rtxremix$worldRenderBeforeDebugRender(CallbackInfo ci) {
        RtxRemixMod.onWorldRenderBeforeDebugRender();
    }

    /** {@code WorldRenderEvents.END}. */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void rtxremix$worldRenderEnd(CallbackInfo ci) {
        RtxRemixMod.onWorldRenderEnd((LevelRenderer) (Object) this);
    }
}

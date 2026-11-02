/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBlockData;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import me.jellysquid.mods.sodium.client.util.task.CancellationToken;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures each Embeddium section build for RTX Remix <b>inline</b>, piggybacking on
 * Embeddium's own block loop in {@code ChunkBuilderMeshingTask.execute} instead of
 * running a separate pass. Embeddium replaces vanilla's section builder, so this is
 * where every terrain section is built, on Embeddium's worker threads or on the render
 * thread when it builds a section synchronously.
 *
 * <ul>
 *   <li>{@code execute} wrap: latches the capture decision once for the whole build,
 *       begins the section, and when the task returns its output finishes the section
 *       and enqueues it for the render thread. A build that is cancelled (Embeddium
 *       returns null) or throws discards its capture.</li>
 *   <li>{@code ChunkBuildBuffers.init}: attaches the task's section info, so the
 *       animated sprites the captured quads use are recorded as Embeddium's own
 *       meshing records them and Embeddium keeps ticking those sprites.</li>
 *   <li>{@code BlockRenderer.renderModel} condition: emits the render type's model
 *       quads to the Remix buffer instead, with the model, Forge model data and seed
 *       Embeddium resolved. Embeddium's GL meshing is skipped: Remix draws the terrain,
 *       and the GL terrain is never shown while capturing.</li>
 *   <li>{@code FluidRenderer.render} condition: emits fluid quads to the Remix buffer
 *       instead of Embeddium's.</li>
 *   <li>{@code isSolidRender} value: the test Embeddium makes last for every block it
 *       visits; ends the block in the emitter, which records opacity and lights. The
 *       value itself is untouched, so Embeddium's occlusion graph is unchanged.</li>
 * </ul>
 *
 * <p>Block entities are registered and the occlusion data resolved by Embeddium
 * exactly as without capture, so its culling and block-entity rendering are
 * unaffected. Embeddium's mesh appenders ({@code ChunkMeshEvent}) and custom block
 * renderers ({@code BlockRendererRegistry}) feed only its own GL buffers and are not
 * captured.
 *
 * <p>Every injector names {@code execute} with its full descriptor: the class also has
 * the compiler's bridge {@code execute(...)Object}, which calls the real one and must not
 * be wrapped a second time.
 *
 * <p>Embeddium's methods are not obfuscated, so every injector here and every
 * Embeddium target is {@code remap = false}; the one Minecraft call target,
 * {@code isSolidRender}, is {@code remap = true} so the refmap carries its SRG name.
 */
@Mixin(ChunkBuilderMeshingTask.class)
public abstract class EmbeddiumMeshingTaskMixin {

    @Shadow(remap = false)
    @Final
    private RenderSection render;

    /**
     * Whether this build captures for Remix is read once, here.
     *
     * <p>Capture needs a running renderer as well as the RTX flag, so a renderer that
     * failed to start leaves Embeddium meshing every section for vanilla's GL draw.
     *
     * <p>{@code rtxEnabled} is volatile so worker threads see a toggle promptly, which
     * means every hook below would otherwise read it independently and a toggle could
     * land <em>between</em> two of them inside one build, routing some blocks of a
     * section into the Remix emitter and the rest into Embeddium's GL path. The hooks
     * read the emitter's active state instead, which this sets for the build and
     * always clears before returning, so a build is wholly in one mode.
     * {@code setRtxEnabled} reloads the world renderer, which replaces Embeddium's
     * section manager, so sections built in the losing mode are rebuilt afterwards.
     */
    @WrapMethod(method = "execute(Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
                    + "Lme/jellysquid/mods/sodium/client/util/task/CancellationToken;)"
                    + "Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;", remap = false)
    private ChunkBuildOutput rtxremix$captureForRemix(
            ChunkBuildContext buildContext,
            CancellationToken cancellationToken,
            Operation<ChunkBuildOutput> original) {
        if (!RtxRemixMod.isRemixPresenting()) {
            return original.call(buildContext, cancellationToken);
        }

        SectionPos sectionPos = this.render.getPosition();
        BlockDataQueue.CaptureToken captureToken =
                BlockDataQueue.beginCapture(sectionPos.getX(), sectionPos.getZ());
        BlockQuadEmitter emitter = BlockQuadEmitter.threadLocal();
        boolean finished = false;
        try (ProfileScope ignored = ProfileScope.begin("SectionBuilder")) {
            BlockTextureManager tex = null;
            RtxRemixMod mod = RtxRemixMod.getInstance();
            RemixRenderer renderer = mod != null ? mod.getRenderer() : null;
            if (renderer != null) {
                tex = renderer.getTextureManager();
            }
            emitter.beginSection(sectionPos, tex, Minecraft.getInstance().getBlockRenderer());

            ChunkBuildOutput output;
            try (ProfileScope blockLoop = ProfileScope.begin("block loop")) {
                output = original.call(buildContext, cancellationToken);
            }
            if (output == null) {
                // Cancelled: Embeddium discards the section, and so does the capture.
                return null;
            }

            // The emitter's mesh data owns pooled buffers from here on; enqueue takes them
            // over and releases them itself when it drops the capture.
            SectionBlockData blockData = emitter.finishSection(sectionPos);
            finished = true;
            BlockDataQueue.enqueue(blockData, captureToken);
            return output;
        } finally {
            if (!finished) {
                emitter.abandonSection();
            }
        }
    }

    /** Lets the emitter record the animated sprites it uses in the section's info. */
    @Inject(
            method = "execute(Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
                    + "Lme/jellysquid/mods/sodium/client/util/task/CancellationToken;)"
                    + "Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
            remap = false,
            at = @At(
                    value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;"
                            + "init(Lme/jellysquid/mods/sodium/client/render/chunk/data/BuiltSectionInfo$Builder;I)V",
                    remap = false))
    private void rtxremix$attachSpriteSink(
            ChunkBuildContext buildContext,
            CancellationToken cancellationToken,
            CallbackInfoReturnable<ChunkBuildOutput> cir,
            @Local BuiltSectionInfo.Builder renderData) {
        BlockQuadEmitter emitter = BlockQuadEmitter.current();
        if (emitter != null && emitter.isActive()) {
            emitter.setAnimatedSpriteSink(renderData::addSprite);
        }
    }

    /**
     * Emits one render type of a MODEL block for Remix and skips Embeddium's GL meshing
     * of it. Embeddium calls this once per render type the model reports, after
     * resolving the model data and unwrapping weighted variants.
     */
    @WrapWithCondition(
            method = "execute(Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
                    + "Lme/jellysquid/mods/sodium/client/util/task/CancellationToken;)"
                    + "Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
            remap = false,
            at = @At(
                    value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;"
                            + "renderModel(Lme/jellysquid/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderContext;"
                            + "Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;)V",
                    remap = false))
    private boolean rtxremix$emitModelForRemix(
            BlockRenderer blockRenderer, BlockRenderContext ctx, ChunkBuildBuffers buffers) {
        BlockQuadEmitter emitter = BlockQuadEmitter.current();
        if (emitter == null || !emitter.isActive()) {
            return true;
        }
        emitter.emitBlock(ctx.state(), ctx.pos(), ctx.world(),
                ctx.model(), ctx.modelData(), ctx.renderLayer(), ctx.seed());
        return false;
    }

    /** Emits fluid quads for Remix and skips Embeddium's GL fluid meshing. */
    @WrapWithCondition(
            method = "execute(Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
                    + "Lme/jellysquid/mods/sodium/client/util/task/CancellationToken;)"
                    + "Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
            remap = false,
            at = @At(
                    value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/chunk/compile/pipeline/FluidRenderer;"
                            + "render(Lme/jellysquid/mods/sodium/client/world/WorldSlice;"
                            + "Lnet/minecraft/world/level/material/FluidState;"
                            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;"
                            + "Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;)V",
                    remap = false))
    private boolean rtxremix$emitFluidForRemix(
            FluidRenderer fluidRenderer, WorldSlice slice, FluidState fluidState,
            BlockPos blockPos, BlockPos offset, ChunkBuildBuffers buffers) {
        BlockQuadEmitter emitter = BlockQuadEmitter.current();
        if (emitter == null || !emitter.isActive()) {
            return true;
        }
        emitter.emitFluid(blockPos, slice, fluidState);
        return false;
    }

    /**
     * Ends the visited block in the emitter: its opacity, its lights, and the flush of
     * the model layers staged above. Embeddium makes this test for every block it does
     * not skip as air, after the block's model layers and fluid.
     */
    @ModifyExpressionValue(
            method = "execute(Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
                    + "Lme/jellysquid/mods/sodium/client/util/task/CancellationToken;)"
                    + "Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
            remap = false,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "isSolidRender(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z",
                    remap = true))
    private boolean rtxremix$captureBlock(
            boolean opaque,
            @Local BlockState blockState,
            @Local WorldSlice slice,
            @Local(ordinal = 0) BlockPos.MutableBlockPos blockPos) {
        BlockQuadEmitter emitter = BlockQuadEmitter.current();
        if (emitter != null && emitter.isActive()) {
            emitter.processBlock(blockState, slice, blockPos, opaque);
        }
        return opaque;
    }
}

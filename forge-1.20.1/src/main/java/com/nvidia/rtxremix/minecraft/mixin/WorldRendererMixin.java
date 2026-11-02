/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.cloud.WorldRendererCloudAccess;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.profiling.WorldRendererProfilingAccess;
import com.nvidia.rtxremix.minecraft.sky.WorldRendererStarAccess;
import com.nvidia.rtxremix.minecraft.weather.RemixWeatherRenderer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.SortedSet;

/**
 * Reroutes vanilla's level render into the Remix capture while a Remix world is
 * active.
 *
 * <ul>
 *   <li>Entities and the block-breaking crack are captured where vanilla draws
 *       them, so each model is evaluated once rather than once per renderer.
 *       Block entities are captured by {@code BlockEntityRenderDispatcherMixin},
 *       which Embeddium's own block-entity walk still reaches. Vanilla's crack loop
 *       is kept in charge while Remix renders, past a mod that replaces it.</li>
 *   <li>The terrain layers, particle draw and cloud draw are cancelled, because
 *       Remix owns that geometry. Each cancellation replays the net GL state its
 *       target leaves, and the terrain layers still post their Forge
 *       {@code RenderLevelStageEvent} stage.</li>
 *   <li>Weather and the star buffer are handed to the Remix passes.</li>
 *   <li>Every vanilla render phase and {@code setupRender} are labelled for Tracy
 *       and JFR.</li>
 * </ul>
 *
 * <p>Embeddium overwrites {@code setupRender}, {@code renderChunkLayer} and the
 * section-dirty methods, nulls vanilla's {@code viewArea}, and renders block
 * entities itself, so nothing here reads vanilla's chunk storage or visible
 * section list. Mixin merges every overwrite before any injector is prepared, so
 * the HEAD cancel of {@code renderChunkLayer} and the {@code setupRender} scopes
 * land on Embeddium's bodies; priority 1100 also applies these injectors after
 * Embeddium's own.
 *
 * <p>{@code renderLevel} itself is never cancelled and never left early: Remix is
 * hosted inside its traversal, and Forge's stage events and other mods' hooks
 * come from it. Every call replacement is a {@code @WrapOperation} so it
 * composes with other mods' redirects and wraps of the same call.
 */
@Mixin(value = LevelRenderer.class, priority = 1100)
public abstract class WorldRendererMixin
        implements WorldRendererCloudAccess, WorldRendererProfilingAccess,
        WorldRendererStarAccess {

    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$renderScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$setupTerrainScope = new ThreadLocal<>();

    @Shadow
    @Nullable
    private ClientLevel level;

    @Shadow
    private int renderedEntities;

    @Shadow
    private int culledEntities;

    @Shadow
    private int ticks;

    @Shadow
    @Final
    private Set<BlockEntity> globalBlockEntities;

    @Shadow
    @Final
    private Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress;

    @Shadow
    public abstract boolean shouldShowEntityOutlines();

    private ProfileScope rtxremix$profilerPhaseScope;
    private String rtxremix$profilerPhaseName;
    private int rtxremix$renderLayerCalls;
    private int rtxremix$renderLayerSkips;
    private int rtxremix$phaseRenderLayerCallsStart;
    private int rtxremix$phaseRenderLayerSkipsStart;
    private RemixWeatherRenderer rtxremix$weatherRenderer;
    private ResourceLocation rtxremix$weatherTexture;
    private float[] rtxremix$starVertices = new float[0];

    @Override
    public int rtxremix$getCloudTicks() {
        return ticks;
    }

    @Inject(method = "drawStars", at = @At("RETURN"))
    private void rtxremix$captureStarVertices(
            BufferBuilder builder,
            CallbackInfoReturnable<BufferBuilder.RenderedBuffer> cir) {
        BufferBuilder.RenderedBuffer renderedBuffer = cir.getReturnValue();
        BufferBuilder.DrawState drawState = renderedBuffer.drawState();
        VertexFormat format = drawState.format();
        int positionIndex = format.getElements().indexOf(DefaultVertexFormat.ELEMENT_POSITION);
        if (positionIndex < 0) {
            rtxremix$starVertices = new float[0];
            return;
        }
        int positionOffset = format.getOffset(positionIndex);

        int vertexCount = drawState.vertexCount();
        int stride = format.getVertexSize();
        // vertexBuffer() is a fresh slice of the builder's storage; createStars uploads it
        // after this returns, so the data is still in place.
        ByteBuffer vertices = renderedBuffer.vertexBuffer().order(ByteOrder.nativeOrder());
        float[] positions = new float[vertexCount * 3];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int source = vertex * stride + positionOffset;
            int destination = vertex * 3;
            positions[destination] = vertices.getFloat(source);
            positions[destination + 1] = vertices.getFloat(source + Float.BYTES);
            positions[destination + 2] = vertices.getFloat(source + 2 * Float.BYTES);
        }
        rtxremix$starVertices = positions;
    }

    @Override
    public float[] rtxremix$copyStarVertices() {
        return rtxremix$starVertices.clone();
    }

    /**
     * Captures entities where vanilla renders them, instead of rendering them twice.
     *
     * <p>Vanilla evaluates the entity model here and writes it to OpenGL. Replacing
     * this call keeps Minecraft in charge of animation, feature layers and modded
     * entity support, but sends the geometry to the Remix capture provider instead,
     * so the model is evaluated once for both renderers.
     *
     * <p>This traversal is frustum culled, so entities the camera cannot see are
     * picked up afterwards by the Remix pass, which needs them for shadows and
     * reflections.
     */
    @WrapOperation(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderEntity("
                            + "Lnet/minecraft/world/entity/Entity;DDDF"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/MultiBufferSource;)V"
            ))
    private void rtxremix$captureEntityInPlace(
            LevelRenderer worldRenderer,
            Entity entity,
            double cameraX,
            double cameraY,
            double cameraZ,
            float tickDelta,
            PoseStack matrices,
            MultiBufferSource vertexConsumers,
            Operation<Void> original) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        boolean captured = mod != null
                && mod.getRenderer() != null
                && mod.getRenderer().renderEntityInPlace(entity, tickDelta);
        if (!captured) {
            // Remix is not drawing this entity, so vanilla's frame is what the user
            // sees and it keeps its blob shadow.
            original.call(worldRenderer, entity, cameraX, cameraY, cameraZ,
                    tickDelta, matrices, vertexConsumers);
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (!shouldShowEntityOutlines() || !client.shouldEntityAppearGlowing(entity)) {
            return;
        }
        // Vanilla hands an entity the outline provider only when the outline post-processor
        // is usable and the entity is outlined (glowing, or a player under the spectator
        // outlines key). That pass writes the framebuffer the outline shader reads, which
        // capturing for Remix does not feed, so vanilla still has to run for it. Its blob
        // shadow must not: the GL frame is composited wholesale over the traced scene, and
        // Remix already traces the real shadow, so the blob would double it.
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        boolean hadShadows = ((EntityRenderDispatcherAccessor) dispatcher).rtxremix$getRenderShadows();
        dispatcher.setRenderShadow(false);
        try {
            original.call(worldRenderer, entity, cameraX, cameraY, cameraZ,
                    tickDelta, matrices, vertexConsumers);
        } finally {
            dispatcher.setRenderShadow(hadShadows);
        }
    }

    /**
     * Captures the block-breaking crack where vanilla renders it, instead of letting it
     * fall into the OpenGL frame that Remix's image replaces.
     *
     * <p>Vanilla's matrix stack and consumer are deliberately not forwarded: the stack
     * is camera-relative and the consumer writes into the GL effect buffer, while the
     * capture supplies an identity stack and its own crack projection so the geometry
     * stays block-local. Forge's model data is forwarded unchanged. When Remix
     * declines, vanilla renders as usual.
     */
    @WrapOperation(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBreakingTexture("
                            + "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/BlockAndTintGetter;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;"
                            + "Lnet/minecraftforge/client/model/data/ModelData;)V",
                    // Forge's ModelData overload has no SRG name; it keeps this one.
                    remap = false
            ))
    private void rtxremix$captureBlockDamageInPlace(
            BlockRenderDispatcher blockRenderManager,
            BlockState state,
            BlockPos pos,
            BlockAndTintGetter world,
            PoseStack matrices,
            VertexConsumer vertexConsumer,
            ModelData modelData,
            Operation<Void> original) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        boolean captured = renderer != null
                && renderer.renderBlockDamageInPlace(pos, rtxremix$blockBreakingStage(pos),
                        (captureMatrices, captureConsumer) -> original.call(
                                blockRenderManager, state, pos, world, captureMatrices, captureConsumer,
                                modelData));
        if (!captured) {
            original.call(blockRenderManager, state, pos, world, matrices, vertexConsumer, modelData);
        }
    }

    /**
     * Keeps vanilla's crack loop, whose {@code renderBreakingTexture} call the capture
     * above wraps, in charge while Remix draws the world. Flerovium (in the Sunlit Valley
     * pack) redirects this entry-set read to draw every crack through its own renderer and
     * hand vanilla an empty set, which would leave Remix with no cracks at all. A wrap
     * applies on top of that redirect, so while Remix renders the set is read from the map
     * directly, and otherwise the call goes to whatever it now is. Without such a redirect
     * both branches return the same set.
     */
    @WrapOperation(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;long2ObjectEntrySet()Lit/unimi/dsi/fastutil/objects/ObjectSet;",
                    remap = false
            ))
    private ObjectSet<Long2ObjectMap.Entry<SortedSet<BlockDestructionProgress>>> rtxremix$keepVanillaCrackLoop(
            Long2ObjectMap<SortedSet<BlockDestructionProgress>> progressions,
            Operation<ObjectSet<Long2ObjectMap.Entry<SortedSet<BlockDestructionProgress>>>> original) {
        if (RtxRemixMod.isRemixWorldRendering()) {
            return progressions.long2ObjectEntrySet();
        }
        return original.call(progressions);
    }

    /**
     * The highest destroy stage among the actors breaking this block, read back from the
     * progress map because the wrapped call receives only the consumer vanilla built for
     * that stage. Vanilla reaches the wrapped call only after finding this position's set
     * non-empty, on the same thread, so the entry is present here.
     */
    private int rtxremix$blockBreakingStage(BlockPos pos) {
        SortedSet<BlockDestructionProgress> progressions = destructionProgress.get(pos.asLong());
        return progressions.last().getProgress();
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void rtxremix$renderHead(
            PoseStack matrices,
            float tickDelta,
            long finishTimeNano,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightmapTextureManager,
            Matrix4f projectionMatrix,
            CallbackInfo ci) {
        rtxremix$openJfrOnly(
                rtxremix$renderScope,
                "WorldRenderer.render",
                "Java.WorldRenderer.render.ms");
        rtxremix$renderLayerCalls = 0;
        rtxremix$renderLayerSkips = 0;
        rtxremix$transitionProfilerPhase("preamble");
        RtxRemixMod mod = RtxRemixMod.getInstance();
        if (mod != null && mod.getRenderer() != null) {
            mod.getRenderer().beginEntityFrame();
        }
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void rtxremix$renderReturn(
            PoseStack matrices,
            float tickDelta,
            long finishTimeNano,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightmapTextureManager,
            Matrix4f projectionMatrix,
            CallbackInfo ci) {
        rtxremix$closeProfilerPhase();
        rtxremix$close(rtxremix$renderScope);
    }

    @WrapOperation(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"
            ))
    private void rtxremix$mirrorVanillaProfilerPhase(
            ProfilerFiller profiler,
            String name,
            Operation<Void> original) {
        original.call(profiler, name);
        rtxremix$transitionProfilerPhase(name);
    }

    /**
     * Skips the terrain draw of a vanilla layer. Under Embeddium this lands on its
     * overwrite, so Embeddium's section draw is skipped. The layer's Forge stage event
     * is still posted exactly as both vanilla and Embeddium post it, inside the layer's
     * render state, so listeners keep their timing; setting the state up and clearing
     * it again leaves the net state unchanged, as the skipped body does.
     */
    @Inject(method = "renderChunkLayer", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaTerrainLayer(
            RenderType renderLayer,
            PoseStack matrices,
            double x,
            double y,
            double z,
            Matrix4f projectionMatrix,
            CallbackInfo ci) {
        rtxremix$renderLayerCalls++;
        if (RtxRemixMod.isRemixWorldRendering() && rtxremix$isVanillaTerrainLayer(renderLayer)) {
            rtxremix$renderLayerSkips++;
            LevelRenderer self = (LevelRenderer) (Object) this;
            renderLayer.setupRenderState();
            ForgeHooksClient.dispatchRenderStage(renderLayer, self, matrices, projectionMatrix, ticks,
                    Minecraft.getInstance().gameRenderer.getMainCamera(), self.getFrustum());
            renderLayer.clearRenderState();
            ci.cancel();
        }
    }

    /**
     * Skips vanilla's particle draw, which Remix replaces, at both of renderLevel's call
     * sites (Fabulous and not), and replays the net state {@code ParticleEngine.render}
     * leaves: depth test on, depth writes on, blending off and the lightmap unbound.
     * Forge's {@code AFTER_PARTICLES} stage is posted by renderLevel after the call and
     * still runs.
     */
    @WrapOperation(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/ParticleEngine;render("
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"
                            + "Lnet/minecraft/client/renderer/LightTexture;"
                            + "Lnet/minecraft/client/Camera;F"
                            + "Lnet/minecraft/client/renderer/culling/Frustum;)V",
                    // Forge's Frustum overload has no SRG name; it keeps this one.
                    remap = false
            ))
    private void rtxremix$skipVanillaParticleDraw(
            ParticleEngine particleManager,
            PoseStack matrices,
            MultiBufferSource.BufferSource bufferSource,
            LightTexture lightmapTextureManager,
            Camera camera,
            float tickDelta,
            Frustum frustum,
            Operation<Void> original) {
        if (!RtxRemixMod.isRemixWorldRendering()) {
            original.call(particleManager, matrices, bufferSource, lightmapTextureManager, camera,
                    tickDelta, frustum);
            return;
        }
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        lightmapTextureManager.turnOffLightLayer();
    }

    /**
     * Skips vanilla's cloud draw when the Remix cloud renderer replaces it. A dimension
     * without a cloud height returns from vanilla's body before touching any state, so
     * only a dimension that has one gets the replayed net state: culling and depth test
     * on, depth and colour writes on, blending off with the default function, and the
     * level fog colour.
     */
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaCloudDraw(
            PoseStack matrices,
            Matrix4f projectionMatrix,
            float tickDelta,
            double cameraX,
            double cameraY,
            double cameraZ,
            CallbackInfo ci) {
        if (RtxRemixMod.isRemixWorldRendering()
                && RtxRemixMod.getInstance()
                        .getRenderer()
                        .getCloudRenderer()
                        .shouldSuppressVanillaClouds()) {
            if (level != null && !Float.isNaN(level.effects().getCloudHeight())) {
                RenderSystem.enableCull();
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(true);
                RenderSystem.colorMask(true, true, true, true);
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
                FogRenderer.levelFogColor();
            }
            ci.cancel();
        }
    }

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"))
    private void rtxremix$beginWeatherCapture(
            LightTexture lightmapTextureManager,
            float tickDelta,
            double cameraX,
            double cameraY,
            double cameraZ,
            CallbackInfo ci) {
        rtxremix$weatherRenderer = rtxremix$getWeatherRenderer();
        rtxremix$weatherTexture = null;
        if (rtxremix$weatherRenderer != null) {
            rtxremix$weatherRenderer.beginCaptureFrame(lightmapTextureManager);
        }
    }

    @WrapOperation(
            method = "renderSnowAndRain",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V"
            ))
    private void rtxremix$captureWeatherTexture(
            int textureUnit,
            ResourceLocation texture,
            Operation<Void> original) {
        if (rtxremix$weatherRenderer != null && textureUnit == 0) {
            rtxremix$weatherTexture = texture;
            return;
        }
        original.call(textureUnit, texture);
    }

    /**
     * 1.20.1's weather draw is {@code Tesselator.end()}, which ends the shared builder and
     * draws the result. While Remix captures, the builder is ended here and its rendered
     * buffer handed to the weather renderer, which draws it after the Remix frame and
     * then releases it.
     */
    @WrapOperation(
            method = "renderSnowAndRain",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/Tesselator;end()V"
            ))
    private void rtxremix$captureWeatherDraw(
            Tesselator tesselator,
            Operation<Void> original) {
        if (rtxremix$weatherRenderer == null) {
            original.call(tesselator);
            return;
        }

        rtxremix$weatherRenderer.capture(
                tesselator.getBuilder().end(),
                rtxremix$weatherTexture);
    }

    @Inject(method = "renderSnowAndRain", at = @At("RETURN"))
    private void rtxremix$endWeatherCapture(
            LightTexture lightmapTextureManager,
            float tickDelta,
            double cameraX,
            double cameraY,
            double cameraZ,
            CallbackInfo ci) {
        rtxremix$weatherRenderer = null;
        rtxremix$weatherTexture = null;
    }

    /**
     * Under Embeddium this brackets its terrain setup, which also runs its chunk
     * update and upload work; vanilla's {@code compileChunks} has nothing left to do.
     */
    @Inject(method = "setupRender", at = @At("HEAD"))
    private void rtxremix$setupTerrainHead(
            Camera camera,
            Frustum frustum,
            boolean hasForcedFrustum,
            boolean spectator,
            CallbackInfo ci) {
        rtxremix$openJfrOnly(
                rtxremix$setupTerrainScope,
                "WorldRenderer.setupTerrain",
                "Java.WorldRenderer.setupTerrain.ms");
    }

    @Inject(method = "setupRender", at = @At("RETURN"))
    private void rtxremix$setupTerrainReturn(
            Camera camera,
            Frustum frustum,
            boolean hasForcedFrustum,
            boolean spectator,
            CallbackInfo ci) {
        rtxremix$close(rtxremix$setupTerrainScope);
    }

    private void rtxremix$transitionProfilerPhase(String vanillaPhaseName) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            rtxremix$closeProfilerPhase();
            return;
        }
        rtxremix$closeProfilerPhase();
        rtxremix$profilerPhaseName = "WorldRenderer.phase." + rtxremix$sanitizePhase(vanillaPhaseName);
        rtxremix$phaseRenderLayerCallsStart = rtxremix$renderLayerCalls;
        rtxremix$phaseRenderLayerSkipsStart = rtxremix$renderLayerSkips;
        rtxremix$profilerPhaseScope = ProfileScope.begin(rtxremix$profilerPhaseName)
                .plotOnClose("Java." + rtxremix$profilerPhaseName + ".ms");
    }

    private void rtxremix$closeProfilerPhase() {
        ProfileScope scope = rtxremix$profilerPhaseScope;
        if (scope == null) {
            return;
        }
        rtxremix$profilerPhaseScope = null;
        rtxremix$annotateProfilerPhase(scope);
        scope.close();
    }

    @Override
    public void rtxremix$closeProfilerPhaseForFabricEvent() {
        rtxremix$closeProfilerPhase();
    }

    private void rtxremix$annotateProfilerPhase(ProfileScope scope) {
        if (level != null) {
            // Embeddium overwrites countRenderedChunks with its own visible-section count;
            // vanilla's visible list stays empty under it.
            int visibleChunks = ((LevelRenderer) (Object) this).countRenderedChunks();
            scope.visibleChunkCount(visibleChunks);
            scope.captureCount(visibleChunks);
        }
        scope.entityCount(renderedEntities);
        scope.blockEntityCount(culledEntities);
        scope.blockCount(culledEntities);
        scope.noCullBlockEntityCount(rtxremix$noCullBlockEntityCount());
        scope.blockBreakingCount(destructionProgress == null ? -1 : destructionProgress.size());
        scope.renderLayerCalls(rtxremix$renderLayerCalls - rtxremix$phaseRenderLayerCallsStart);
        scope.renderLayerSkips(rtxremix$renderLayerSkips - rtxremix$phaseRenderLayerSkipsStart);
    }

    private int rtxremix$noCullBlockEntityCount() {
        if (globalBlockEntities == null) {
            return -1;
        }
        synchronized (globalBlockEntities) {
            return globalBlockEntities.size();
        }
    }

    private static String rtxremix$sanitizePhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return "unknown";
        }
        return phase.replace(' ', '_');
    }

    private static void rtxremix$openJfrOnly(
            ThreadLocal<ArrayDeque<ProfileScope>> holder,
            String phaseName,
            String plotName) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        rtxremix$push(holder, ProfileScope.beginJfrOnly(phaseName).plotOnClose(plotName));
    }

    private static void rtxremix$push(
            ThreadLocal<ArrayDeque<ProfileScope>> holder,
            ProfileScope scope) {
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            holder.set(stack);
        }
        stack.push(scope);
    }

    private static void rtxremix$close(ThreadLocal<ArrayDeque<ProfileScope>> holder) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            ProfileScope scope = stack.pop();
            if (stack.isEmpty()) {
                holder.remove();
            }
            scope.close();
        } else if (stack != null) {
            holder.remove();
        }
    }

    private static boolean rtxremix$isVanillaTerrainLayer(RenderType renderLayer) {
        return renderLayer == RenderType.solid()
                || renderLayer == RenderType.cutoutMipped()
                || renderLayer == RenderType.cutout()
                || renderLayer == RenderType.translucent()
                || renderLayer == RenderType.tripwire();
    }

    private static RemixWeatherRenderer rtxremix$getWeatherRenderer() {
        if (!RtxRemixMod.isRemixWorldRendering()) {
            return null;
        }
        return RtxRemixMod.getInstance().getRenderer().getWeatherRenderer();
    }
}

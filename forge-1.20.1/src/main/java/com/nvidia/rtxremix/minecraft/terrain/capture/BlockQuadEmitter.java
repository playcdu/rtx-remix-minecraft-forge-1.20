/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.minecraft.light.BlockLightRuleManager;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.tags.FluidTags;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Per-block mesh emission for RTX Remix, driven from
 * {@link com.nvidia.rtxremix.minecraft.mixin.EmbeddiumMeshingTaskMixin}, which
 * piggybacks on Embeddium's own block iteration loop in
 * {@code ChunkBuilderMeshingTask.execute}.
 *
 * <p>Lifecycle per section:
 * <ol>
 *   <li>{@link #beginSection}: reset state, called before the task's block loop starts</li>
 *   <li>{@link #emitBlock}: one render type's model quads, called in place of each
 *       {@code BlockRenderer.renderModel} call Embeddium makes for a MODEL block, so
 *       Embeddium's GL meshing never runs while capturing</li>
 *   <li>{@link #emitFluid}: fluid quads, called in place of {@code FluidRenderer.render}</li>
 *   <li>{@link #processBlock}: flushes the block's staged quads and records opacity and
 *       lights, called from the {@code isSolidRender} test Embeddium makes last for every
 *       block it visits</li>
 *   <li>{@link #finishSection}: build SectionBlockData once the task returns its output,
 *       or {@link #abandonSection} when it is cancelled or throws</li>
 * </ol>
 *
 * <p>Embeddium skips air that has no block entity without calling anything above, so
 * nothing here may depend on visiting every position: an unvisited cell is air, which
 * is exactly what the zeroed opacity bitmap records, and the boundary face masks are
 * derived from that bitmap when the section finishes.
 *
 * <p>One instance per worker thread via {@code ThreadLocal}.
 */
public final class BlockQuadEmitter {

    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };
    private static final Direction[] ALL_DIRECTIONS = {
        null, Direction.UP, Direction.DOWN, Direction.NORTH,
        Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private static final ThreadLocal<BlockQuadEmitter> INSTANCE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> threadNamed =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final SingleThreadedRandomSource random = new SingleThreadedRandomSource(42);
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos fluidPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos fluidCheckPos = new BlockPos.MutableBlockPos();
    private final DirectQuadWriter writer = new DirectQuadWriter();
    private final ModelSampling modelSampling = new ModelSampling();
    private final List<LightEntry> lights = new ArrayList<>();

    private static final int TINT_CACHE_SIZE = 8;
    private static final int SECTION_SIZE = 16;
    private static final int LOCAL_BLOCK_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int WATER_COLOR_CACHE_SIZE = 4 * 2 * 4;
    private static final float WATER_CEILING_CULL_MIN_HEIGHT = 8.0f / 9.0f;
    private static final float WATER_HEIGHT_EPSILON = 0.0001f;
    private static final int WATER_TOP_CULL_RADIUS = 2;
    private static final int WATER_TOP_CULL_WIDTH = WATER_TOP_CULL_RADIUS * 2 + 1;
    private static final int WATER_TOP_CULL_CELL_COUNT = WATER_TOP_CULL_WIDTH * WATER_TOP_CULL_WIDTH;
    private static final int WATER_TOP_CACHE_WIDTH = SECTION_SIZE + WATER_TOP_CULL_RADIUS * 2;
    private static final int WATER_TOP_CACHE_CELL_COUNT =
            WATER_TOP_CACHE_WIDTH * SECTION_SIZE * WATER_TOP_CACHE_WIDTH;
    // BlockState render type is immutable; cache by raw state id to avoid a per-block virtual call.
    private static volatile RenderShape[] renderTypeByStateId = new RenderShape[0];

    private final float[] tempPositions = new float[12];
    private final float[] tempUvs = new float[8];
    private final int[] tintCache = new int[TINT_CACHE_SIZE];
    private final int[] waterColorCache = new int[WATER_COLOR_CACHE_SIZE];
    private final byte[] waterTopCellCache = new byte[WATER_TOP_CACHE_CELL_COUNT];
    private final int[] waterTopCullStack = new int[WATER_TOP_CULL_CELL_COUNT];
    private final FluidTopOcclusion.CellClassifier waterTopCellClassifier =
            this::classifyWaterTopCellAtOffset;
    private final BlockState[] blockStateCache = new BlockState[LOCAL_BLOCK_COUNT];
    private final FluidState[] fluidStateCache = new FluidState[LOCAL_BLOCK_COUNT];

    /**
     * Results of {@link Block#shouldRenderFace}, keyed like vanilla's own cache by (state,
     * neighbour state, side) and therefore position-independent to the same degree. Vanilla's
     * cache allocates its lookup key on every opaque-neighbour face; this direct-mapped table
     * of packed state ids allocates nothing on a hit and defers to vanilla on a miss.
     *
     * <p>Forge adds one position-dependent rule to {@code shouldRenderFace}: a neighbour may
     * hide the face through {@code hidesNeighborFace}, which a copycat or framed block
     * answers from its block entity. That rule is evaluated before the table on every face
     * and never cached, so a cached entry only ever holds the state-keyed remainder.
     */
    private static final int FACE_CULL_CACHE_BITS = 13;
    private static final byte FACE_CULL_UNKNOWN = 0;
    private static final byte FACE_CULL_HIDDEN = 1;
    private static final byte FACE_CULL_DRAWN = 2;
    private final long[] faceCullKeys = new long[1 << FACE_CULL_CACHE_BITS];
    private final byte[] faceCullResults = new byte[1 << FACE_CULL_CACHE_BITS];

    /**
     * Model-randomisation seed for the block being emitted, the
     * {@code state.getSeed(pos)} Embeddium hands its own renderer. Set by every
     * {@link #emitBlock} call.
     *
     * <p>A multi-variant block picks its variant from this, so a constant makes
     * every instance identical. Where the variants are rotations of one model
     * that is not merely a repeated texture but misplaced geometry: a sea
     * pickle cluster rotates its pickles around the block.
     */
    private long modelRandomSeed = 42L;

    /** Own random for {@link #classifyBlockLayer(BlockState)}, which any thread may call. */
    private static final ThreadLocal<SingleThreadedRandomSource> LAYER_RANDOM =
            ThreadLocal.withInitial(() -> new SingleThreadedRandomSource(42L));

    // Per-section state set by beginSection / processBlock
    /**
     * Whether {@link #beginSection} has run without a matching {@link #finishSection} or
     * {@link #abandonSection}. Capture is gated on it. The meshing hook ends every section
     * it begins, whichever way the build leaves; should one ever be left open, the next
     * build on this thread releases the writer's grown buffers before it starts.
     */
    private boolean active;
    /**
     * Whether a model layer of the block being visited has been emitted since the last
     * {@link #processBlock}. Embeddium renders a block's layers back to back and tests
     * {@code isSolidRender} after them, so the first layer starts the block and
     * {@code processBlock} ends it.
     */
    private boolean blockStarted;
    private BlockTextureManager texManager;
    private BlockRenderDispatcher blockRenderManager;
    private BlockColors blockColors;
    private TextureAtlas blockAtlas;
    /**
     * Receives every sprite the section's quads use, so Embeddium records the animated
     * ones in the section's info exactly as its own meshing does and keeps ticking them.
     * Null when no consumer is attached.
     */
    private Consumer<TextureAtlasSprite> animatedSpriteSink;
    private TextureAtlasSprite lastSinkSprite;
    private int baseX, baseY, baseZ;
    private long topMask, bottomMask, eastMask, westMask, southMask, northMask;
    private final long[] opacity = new long[64];
    private final long[] blockStateKnown = new long[64];
    private final long[] fluidStateKnown = new long[64];
    private int blockCount, fluidCount;
    private int tintValidMask;
    private int waterColorKnownMask;
    private FluidState waterTopCellCacheFluid;
    private BlockAndTintGetter waterTopCullWorld;
    private FluidState waterTopCullReferenceFluid;
    private int waterTopCullOriginX, waterTopCullOriginY, waterTopCullOriginZ;

    private BlockQuadEmitter() {}

    /** Returns the per-thread instance. */
    public static BlockQuadEmitter threadLocal() {
        BlockQuadEmitter emitter = INSTANCE.get();
        if (emitter == null) {
            emitter = new BlockQuadEmitter();
            INSTANCE.set(emitter);
        }
        return emitter;
    }

    /** Returns the current per-thread instance without allocating one. */
    public static BlockQuadEmitter current() {
        return INSTANCE.get();
    }

    public static int getWaterTopOcclusionRadius() {
        return WATER_TOP_CULL_RADIUS;
    }

    // --- Section lifecycle (called from mixin) ---

    /**
     * Initialises emission state for a new section. Called from
     * EmbeddiumMeshingTaskMixin before Embeddium's block loop starts.
     */
    public void beginSection(SectionPos sectionPos,
                             BlockTextureManager tex,
                             BlockRenderDispatcher brm) {
        if (!threadNamed.get()) {
            RemixAPI.tracyThreadName("Worker " + Thread.currentThread().getName());
            threadNamed.set(Boolean.TRUE);
        }
        if (active) {
            writer.releaseBuffers();
        }
        this.active = true;
        this.blockStarted = false;
        this.texManager = tex;
        this.blockRenderManager = brm;
        this.animatedSpriteSink = null;
        this.lastSinkSprite = null;
        this.baseX = sectionPos.minBlockX();
        this.baseY = sectionPos.minBlockY();
        this.baseZ = sectionPos.minBlockZ();

        Minecraft client = Minecraft.getInstance();
        this.blockColors = (client != null) ? client.getBlockColors() : null;
        this.blockAtlas = (client != null)
                ? client.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS)
                : null;

        writer.beginSection(baseX, baseY, baseZ);
        lights.clear();
        topMask = bottomMask = eastMask = westMask = southMask = northMask = 0;
        Arrays.fill(opacity, 0);
        Arrays.fill(blockStateKnown, 0);
        Arrays.fill(fluidStateKnown, 0);
        // Clear the water classification cache lazily on the first eligible
        // surface so sections without covered water pay no cache-reset cost.
        waterTopCellCacheFluid = null;
        waterColorKnownMask = 0;
        blockCount = 0;
        fluidCount = 0;
    }

    /**
     * Attaches the consumer that receives the sprites this section's quads use, for the
     * section being built. Cleared by the next {@link #beginSection}.
     */
    public void setAnimatedSpriteSink(@Nullable Consumer<TextureAtlasSprite> sink) {
        this.animatedSpriteSink = sink;
        this.lastSinkSprite = null;
    }

    public boolean isActive() { return active; }
    public int getBlockCount() { return blockCount; }
    public int getFluidCount() { return fluidCount; }

    public static RenderShape getRenderTypeCached(BlockState state) {
        int id = Block.getId(state);
        RenderShape[] cache = renderTypeByStateId;
        if (id < cache.length) {
            RenderShape renderType = cache[id];
            if (renderType != null) {
                return renderType;
            }
        }

        RenderShape renderType = state.getRenderShape();
        cache = ensureRenderTypeCacheCapacity(id);
        cache[id] = renderType;
        return renderType;
    }

    private static RenderShape[] ensureRenderTypeCacheCapacity(int id) {
        RenderShape[] cache = renderTypeByStateId;
        if (id < cache.length) {
            return cache;
        }

        synchronized (BlockQuadEmitter.class) {
            cache = renderTypeByStateId;
            if (id >= cache.length) {
                int newSize = Math.max(id + 1, Math.max(1024, cache.length * 2));
                renderTypeByStateId = Arrays.copyOf(cache, newSize);
            }
            return renderTypeByStateId;
        }
    }

    /**
     * Ends the visited block: flushes its staged model quads, then captures its
     * opacity bit and light-emitting rules. Called once per visited block from the
     * {@code isSolidRender} test, which Embeddium makes after the block's model layers
     * and fluid. The boundary face masks are derived from the opacity bitmap in
     * {@link #finishSection}, because Embeddium never visits plain air.
     */
    public void processBlock(BlockState state, BlockGetter world, BlockPos pos, boolean opaque) {
        if (blockStarted) {
            writer.flushBlock(texManager);
            blockStarted = false;
        }

        int lx = SectionPos.sectionRelative(pos.getX());
        int ly = SectionPos.sectionRelative(pos.getY());
        int lz = SectionPos.sectionRelative(pos.getZ());

        int bitIdx = (ly << 8) | (lz << 4) | lx;
        cacheLocalBlockState(bitIdx, state);

        long bit = 1L << (bitIdx & 63);
        int word = bitIdx >> 6;
        if (opaque) {
            opacity[word] |= bit;
        } else {
            opacity[word] &= ~bit;
        }

        if (state.isAir()) return;
        if (!BlockLightRuleManager.hasMatchingRules(state)) return;

        // The sampler needs the same seed the mesh uses, so a rule reading
        // model geometry sees the variant this position actually draws.
        //
        // Lights resolve against the unoffset block position, and the sampler
        // reads the unoffset baked model, while emitBlock folds
        // state.getOffset into the geometry origin. No vanilla block
        // combines a BlockBehaviour.OffsetType with a luminance, so the two
        // frames agree for every vanilla block; a modded or resource-pack rule
        // on an offset block would need the same offset passed into begin() and
        // into the LightEntry positions.
        modelSampling.begin(blockRenderManager, state, state.getSeed(pos));
        List<LightEntry> resolvedLights = BlockLightRuleManager.resolve(state, pos, modelSampling);
        lights.addAll(resolvedLights);
    }

    /**
     * Stages one render type's model quads for the block being visited. Called in place
     * of each {@code BlockRenderer.renderModel} call Embeddium makes for a MODEL block,
     * with what Embeddium resolved for it: the model (unwrapped from its weighted
     * variants), the block's Forge {@link ModelData}, the render type, and the
     * per-position seed. Bypasses Embeddium's renderer and writes to the
     * DirectQuadWriter; the staged quads of all the block's layers are flushed together
     * by {@link #processBlock}, so overlay detection sees the whole block.
     */
    public void emitBlock(BlockState state, BlockPos pos, BlockAndTintGetter world,
                          BakedModel model, ModelData modelData, RenderType renderType, long seed) {
        if (!blockStarted) {
            blockStarted = true;
            blockCount++;
            mutablePos.set(pos);
            tintValidMask = 0;
        }

        // Embeddium derives model randomisation from the block position exactly as
        // vanilla's BlockModelRenderer does, and re-seeds before every getQuads call,
        // which the loop below matches.
        modelRandomSeed = seed;

        float lx = SectionPos.sectionRelative(pos.getX());
        float ly = SectionPos.sectionRelative(pos.getY());
        float lz = SectionPos.sectionRelative(pos.getZ());

        // Embeddium applies the per-position model offset (BlockBehaviour.OffsetType
        // XZ/XYZ: flowers, saplings, grass, dripstone) inside
        // BlockRenderer.renderModel, which this emitter replaces wholesale.
        // Applying it here is what keeps Remix geometry on the same spot as
        // Embeddium's.
        //
        // Folded into the section-local origin rather than applied per vertex:
        // the model path already adds that origin to every position, so the
        // offset rides along for free. hasOffsetFunction() is a bare null check on
        // the block's offsetter, while getOffset() allocates a Vec3, so
        // the gate keeps that allocation off a path that runs for every block in
        // every section build.
        //
        // This moves geometry only. Block lights are resolved in processBlock
        // against the unoffset grid position; see the note there before adding a
        // light rule to a block that offsets.
        if (state.hasOffsetFunction()) {
            Vec3 modelOffset = state.getOffset(world, pos);
            lx += (float) modelOffset.x;
            ly += (float) modelOffset.y;
            lz += (float) modelOffset.z;
        }

        String layerName = classifyBlockLayer(state, renderType);

        for (Direction dir : ALL_DIRECTIONS) {
            if (dir != null && !shouldDrawBlockSide(world, state, dir)) {
                continue;
            }

            random.setSeed(modelRandomSeed);
            List<BakedQuad> bakedQuads = model.getQuads(state, dir, random, modelData, renderType);

            for (int i = 0, n = bakedQuads.size(); i < n; i++) {
                stageBakedQuad(bakedQuads.get(i), layerName, state, world, lx, ly, lz);
            }
        }
    }

    /**
     * Emits fluid quads for the current block. Called in place of Embeddium's
     * {@code FluidRenderer.render}.
     */
    public void emitFluid(BlockPos pos, BlockAndTintGetter world, FluidState fluidState) {
        fluidCount++;
        int lx = SectionPos.sectionRelative(pos.getX());
        int ly = SectionPos.sectionRelative(pos.getY());
        int lz = SectionPos.sectionRelative(pos.getZ());
        cacheLocalFluidState(localIndex(lx, ly, lz), fluidState);
        emitFluidQuads(world, fluidState, lx, ly, lz);
    }

    /**
     * Finalises the section, producing a {@link SectionBlockData} with
     * all accumulated mesh/light/mask data. Deactivates the emitter.
     */
    public SectionBlockData finishSection(SectionPos sectionPos) {
        try (ProfileScope finishScope = ProfileScope.begin("finishSection")) {
            finishScope.blockCount(blockCount).fluidCount(fluidCount);
            if (blockStarted) {
                writer.flushBlock(texManager);
                blockStarted = false;
            }
            computeFaceMasks();
            SectionMeshData meshData = writer.buildMeshData();
            SectionBlockData result;
            try {
                result = new SectionBlockData(
                        sectionPos, meshData, List.copyOf(lights), topMask, bottomMask,
                        eastMask, westMask, southMask, northMask,
                        Arrays.copyOf(opacity, opacity.length));
            } catch (RuntimeException failure) {
                // The mesh owns pooled buffers from here on; nothing else would release them.
                meshData.release();
                throw failure;
            }
            lights.clear();
            return result;
        } finally {
            active = false;
            animatedSpriteSink = null;
            lastSinkSprite = null;
        }
    }

    /**
     * Drops the section in progress without producing data, for a build that was
     * cancelled or threw. Nothing pooled is held yet: the writer's work buffers are
     * its own and are reset by the next {@link #beginSection}, and the section's mesh
     * data only exists once {@link #finishSection} builds it.
     */
    public void abandonSection() {
        active = false;
        blockStarted = false;
        lights.clear();
        animatedSpriteSink = null;
        lastSinkSprite = null;
    }

    /**
     * Derives the boundary face masks from the opacity bitmap: a 2x2 sub-region of a
     * boundary face is open when any of its cells is not an opaque full cube. Every cell
     * Embeddium skipped is air and therefore open, which the zeroed bitmap already says.
     */
    private void computeFaceMasks() {
        topMask = bottomMask = eastMask = westMask = southMask = northMask = 0;
        // Each face is an 8x8 grid of 2x2 sub-regions indexed (a >> 1) * 8 + (b >> 1),
        // with (a, b) = (lz, lx) on top/bottom, (ly, lz) on east/west and (ly, lx) on
        // south/north, as SectionBlockData documents.
        for (int a = 0; a < SECTION_SIZE; a++) {
            for (int b = 0; b < SECTION_SIZE; b++) {
                long bit = 1L << ((a >> 1) * 8 + (b >> 1));
                if (!isOpaque(b, 15, a)) topMask |= bit;
                if (!isOpaque(b, 0, a)) bottomMask |= bit;
                if (!isOpaque(15, a, b)) eastMask |= bit;
                if (!isOpaque(0, a, b)) westMask |= bit;
                if (!isOpaque(b, a, 15)) southMask |= bit;
                if (!isOpaque(b, a, 0)) northMask |= bit;
            }
        }
    }

    private boolean isOpaque(int lx, int ly, int lz) {
        int idx = localIndex(lx, ly, lz);
        return (opacity[idx >> 6] & (1L << (idx & 63))) != 0;
    }

    private void stageBakedQuad(BakedQuad bq, String layerName,
                                 BlockState state, BlockAndTintGetter world,
                                 float lx, float ly, float lz) {
        int[] vd = bq.getVertices();

        for (int v = 0; v < 4; v++) {
            int off = v * 8;
            tempPositions[v * 3]     = Float.intBitsToFloat(vd[off])     + lx;
            tempPositions[v * 3 + 1] = Float.intBitsToFloat(vd[off + 1]) + ly;
            tempPositions[v * 3 + 2] = Float.intBitsToFloat(vd[off + 2]) + lz;
            tempUvs[v * 2]     = Float.intBitsToFloat(vd[off + 4]);
            tempUvs[v * 2 + 1] = Float.intBitsToFloat(vd[off + 5]);
        }

        int packedNormal = vd[7];
        float nx = (byte) (packedNormal & 0xFF) / 127f;
        float ny = (byte) ((packedNormal >> 8) & 0xFF) / 127f;
        float nz = (byte) ((packedNormal >> 16) & 0xFF) / 127f;
        if (nx * nx + ny * ny + nz * nz < 0.01f) {
            float e1x = tempPositions[3] - tempPositions[0], e1y = tempPositions[4] - tempPositions[1], e1z = tempPositions[5] - tempPositions[2];
            float e2x = tempPositions[6] - tempPositions[0], e2y = tempPositions[7] - tempPositions[1], e2z = tempPositions[8] - tempPositions[2];
            nx = e1y * e2z - e1z * e2y;
            ny = e1z * e2x - e1x * e2z;
            nz = e1x * e2y - e1y * e2x;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 0.001f) { nx /= len; ny /= len; nz /= len; }
        }

        int color = 0xFFFFFFFF;
        if (bq.getTintIndex() >= 0 && blockColors != null) {
            int tint = getBlockTint(state, world, bq.getTintIndex());
            int r = (tint >> 16) & 0xFF, g = (tint >> 8) & 0xFF, b = tint & 0xFF;
            color = 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        TextureAtlasSprite sprite = bq.getSprite();
        ResourceLocation spriteId = sprite != null ? sprite.contents().name() : null;
        if (sprite != null) {
            offerSprite(sprite);
        }
        if (isWaterSprite(spriteId)) {
            emitBlockWaterQuad(nx, ny, nz, color, layerName);
            return;
        }
        float su0 = sprite != null ? sprite.getU0() : 0;
        float sv0 = sprite != null ? sprite.getV0() : 0;
        float su1 = sprite != null ? sprite.getU1() : 1;
        float sv1 = sprite != null ? sprite.getV1() : 1;

        writer.stageQuad(tempPositions, tempUvs, nx, ny, nz, color,
                spriteId, su0, sv0, su1, sv1, layerName);
    }

    private static final ResourceLocation WATER_STILL_SPRITE = new ResourceLocation("block/water_still");
    private static final ResourceLocation WATER_FLOW_SPRITE = new ResourceLocation("block/water_flow");

    /** A block-model face painted with the water texture: the cauldron's water level is the vanilla case. */
    static boolean isWaterSprite(ResourceLocation spriteId) {
        return WATER_STILL_SPRITE.equals(spriteId) || WATER_FLOW_SPRITE.equals(spriteId);
    }

    /**
     * Emits the staged quad in {@code tempPositions} as RTX water instead of an
     * atlas-textured face, so a cauldron's water matches the lake outside. The
     * water material tiles in world space, so the UVs come from the vertex
     * positions along the face's dominant axes, exactly as the fluid path maps
     * its top and side faces.
     */
    private void emitBlockWaterQuad(float nx, float ny, float nz, int color, String layerName) {
        float tileSize = BlockTextureManager.getWaterTileSize();
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        for (int v = 0; v < 4; v++) {
            float wx = baseX + tempPositions[v * 3];
            float wy = baseY + tempPositions[v * 3 + 1];
            float wz = baseZ + tempPositions[v * 3 + 2];
            float u, w;
            if (ay >= ax && ay >= az) {
                u = wx; w = wz;
            } else if (ax >= az) {
                u = wz; w = wy;
            } else {
                u = wx; w = wy;
            }
            tempUvs[v * 2] = u / tileSize;
            tempUvs[v * 2 + 1] = w / tileSize;
        }
        writer.writeFluidQuad(tempPositions, tempUvs, nx, ny, nz, color,
                null, 0, 0, 1, 1, layerName, texManager);
    }

    // --- Fluid geometry ---

    private void emitFluidQuads(BlockAndTintGetter world, FluidState fluidState,
                                 int lx, int ly, int lz) {
        if (blockAtlas == null) return;
        // Forge's replacement for Fabric's FluidRenderHandler: every fluid type carries
        // its client extension, which names the still and flowing textures and the tint.
        IClientFluidTypeExtensions fluidType = IClientFluidTypeExtensions.of(fluidState);

        fluidPos.set(baseX + lx, baseY + ly, baseZ + lz);

        // The atlas lookups are what ForgeHooksClient.getFluidSprites does, without the
        // array it allocates for every fluid block.
        ResourceLocation stillTexture = fluidType.getStillTexture(fluidState, world, fluidPos);
        ResourceLocation flowTexture = fluidType.getFlowingTexture(fluidState, world, fluidPos);
        if (stillTexture == null || flowTexture == null) return;
        TextureAtlasSprite stillSprite = blockAtlas.getSprite(stillTexture);
        TextureAtlasSprite flowSprite = blockAtlas.getSprite(flowTexture);
        offerSprite(stillSprite);
        offerSprite(flowSprite);

        boolean isLava = fluidState.is(FluidTags.LAVA);
        boolean isWater = !isLava;
        String layer = isLava ? "solid" : "translucent";
        int fluidColor = isWater
                ? getCachedWaterColor(fluidType, world, fluidState, lx, ly, lz)
                : fluidType.getTintColor(fluidState, world, fluidPos);
        int r = (fluidColor >> 16) & 0xFF, g = (fluidColor >> 8) & 0xFF, b = fluidColor & 0xFF;
        int color = 0xFF000000 | (r << 16) | (g << 8) | b;

        float h00 = getFluidHeight(world, fluidState, baseX + lx, baseY + ly, baseZ + lz);
        float h10 = getFluidHeight(world, fluidState, baseX + lx + 1, baseY + ly, baseZ + lz);
        float h01 = getFluidHeight(world, fluidState, baseX + lx, baseY + ly, baseZ + lz + 1);
        float h11 = getFluidHeight(world, fluidState, baseX + lx + 1, baseY + ly, baseZ + lz + 1);

        float tileSize = BlockTextureManager.getWaterTileSize();
        int wx = baseX + lx, wy = baseY + ly, wz = baseZ + lz;

        ResourceLocation stillId = stillSprite.contents().name();
        float su0 = stillSprite.getU0(), sv0 = stillSprite.getV0();
        float su1 = stillSprite.getU1(), sv1 = stillSprite.getV1();

        ResourceLocation flowId = flowSprite.contents().name();
        float fu0 = flowSprite.getU0(), fv0 = flowSprite.getV0();
        float fu1 = flowSprite.getU1(), fv1 = flowSprite.getV1();

        fluidCheckPos.set(fluidPos.getX(), fluidPos.getY() + 1, fluidPos.getZ());
        FluidState aboveFluid = getCachedFluidState(world,
                fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
        boolean submerged = aboveFluid.getType().isSame(fluidState.getType());
        boolean waterTopCovered = false;
        if (isWater && !submerged && fluidState.isSource()) {
            BlockState aboveState = getCachedBlockState(world,
                    fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
            float topCoverageHeight = Math.min(Math.min(h00, h10), Math.min(h01, h11));
            if (isFluidTopCoveredByCeiling(world, topCoverageHeight, fluidCheckPos, aboveState)) {
                waterTopCovered = classifyWaterTopExposure(world, fluidState, wx, wy, wz)
                        == FluidTopOcclusion.Exposure.HIDDEN;
            }
        }

        // Top face
        if (!submerged && !waterTopCovered) {
            setPos4(tempPositions, lx, ly + h00, lz, lx + 1, ly + h10, lz,
                    lx + 1, ly + h11, lz + 1, lx, ly + h01, lz + 1);
            if (isWater) {
                float u0 = wx / tileSize, v0 = wz / tileSize;
                float u1 = (wx + 1) / tileSize, v1 = (wz + 1) / tileSize;
                setUv4(tempUvs, u0, v0, u1, v0, u1, v1, u0, v1);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, 1, 0, color,
                        null, 0, 0, 1, 1, layer, texManager);
                setPos4(tempPositions, lx, ly + h01, lz + 1, lx + 1, ly + h11, lz + 1,
                        lx + 1, ly + h10, lz, lx, ly + h00, lz);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                        null, 0, 0, 1, 1, layer, texManager);
            } else {
                // Vanilla FluidRenderer: a still surface shows the still sprite in
                // full; a moving one shows the centre quarter of the flowing sprite
                // (two tiles across, so a quarter is one tile) rotated to the flow.
                Vec3 flow = fluidState.getFlow(world, fluidPos);
                if (flow.x == 0.0 && flow.z == 0.0) {
                    setUv4(tempUvs, su0, sv0, su1, sv0, su1, sv1, su0, sv1);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, 1, 0, color,
                            stillId, su0, sv0, su1, sv1, layer, texManager);
                    setPos4(tempPositions, lx, ly + h01, lz + 1, lx + 1, ly + h11, lz + 1,
                            lx + 1, ly + h10, lz, lx, ly + h00, lz);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                            stillId, su0, sv0, su1, sv1, layer, texManager);
                } else {
                    float angle = (float) Mth.atan2(flow.z, flow.x) - (float) (Math.PI / 2.0);
                    float sin = Mth.sin(angle) * 0.25f;
                    float cos = Mth.cos(angle) * 0.25f;
                    // Vanilla's corners in its order (0,0) (0,1) (1,1) (1,0), reordered to
                    // this emitter's (0,0) (1,0) (1,1) (0,1).
                    float c00u = frameU(flowSprite, 0.5f - cos - sin), c00v = frameV(flowSprite, 0.5f - cos + sin);
                    float c01u = frameU(flowSprite, 0.5f - cos + sin), c01v = frameV(flowSprite, 0.5f + cos + sin);
                    float c11u = frameU(flowSprite, 0.5f + cos + sin), c11v = frameV(flowSprite, 0.5f + cos - sin);
                    float c10u = frameU(flowSprite, 0.5f + cos - sin), c10v = frameV(flowSprite, 0.5f - cos - sin);
                    setUv4(tempUvs, c00u, c00v, c10u, c10v, c11u, c11v, c01u, c01v);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, 1, 0, color,
                            flowId, fu0, fv0, fu1, fv1, layer, texManager);
                    setPos4(tempPositions, lx, ly + h01, lz + 1, lx + 1, ly + h11, lz + 1,
                            lx + 1, ly + h10, lz, lx, ly + h00, lz);
                    setUv4(tempUvs, c01u, c01v, c11u, c11v, c10u, c10v, c00u, c00v);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                            flowId, fu0, fv0, fu1, fv1, layer, texManager);
                }
            }
        }

        // Bottom face
        fluidCheckPos.set(fluidPos.getX(), fluidPos.getY() - 1, fluidPos.getZ());
        FluidState belowFluid = getCachedFluidState(world,
                fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
        BlockState belowState = getCachedBlockState(world,
                fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
        if (!belowFluid.getType().isSame(fluidState.getType()) && !belowState.isSolidRender(world, fluidCheckPos)) {
            setPos4(tempPositions, lx, ly, lz + 1, lx + 1, ly, lz + 1,
                    lx + 1, ly, lz, lx, ly, lz);
            if (isWater) {
                float u0 = wx / tileSize, v0 = wz / tileSize;
                float u1 = (wx + 1) / tileSize, v1 = (wz + 1) / tileSize;
                setUv4(tempUvs, u0, v1, u1, v1, u1, v0, u0, v0);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                        null, 0, 0, 1, 1, layer, texManager);
            } else {
                setUv4(tempUvs, su0, sv0, su1, sv0, su1, sv1, su0, sv1);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                        stillId, su0, sv0, su1, sv1, layer, texManager);
            }
        }

        // Side faces
        for (Direction dir : HORIZONTAL_DIRECTIONS) {
            fluidCheckPos.set(
                fluidPos.getX() + dir.getStepX(),
                fluidPos.getY(),
                fluidPos.getZ() + dir.getStepZ());
            FluidState sideFluid = getCachedFluidState(world,
                    fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
            BlockState sideState = getCachedBlockState(world,
                    fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());

            if (sideFluid.getType().isSame(fluidState.getType())) continue;
            if (sideState.isSolidRender(world, fluidCheckPos)) continue;

            float sideH0, sideH1;
            switch (dir) {
                case NORTH -> {
                    sideH0 = h10; sideH1 = h00;
                    setPos4(tempPositions, lx + 1, ly, lz, lx, ly, lz,
                            lx, ly + sideH1, lz, lx + 1, ly + sideH0, lz);
                }
                case SOUTH -> {
                    sideH0 = h01; sideH1 = h11;
                    setPos4(tempPositions, lx, ly, lz + 1, lx + 1, ly, lz + 1,
                            lx + 1, ly + sideH1, lz + 1, lx, ly + sideH0, lz + 1);
                }
                case EAST -> {
                    sideH0 = h11; sideH1 = h10;
                    setPos4(tempPositions, lx + 1, ly, lz + 1, lx + 1, ly, lz,
                            lx + 1, ly + sideH1, lz, lx + 1, ly + sideH0, lz + 1);
                }
                case WEST -> {
                    sideH0 = h00; sideH1 = h01;
                    setPos4(tempPositions, lx, ly, lz, lx, ly, lz + 1,
                            lx, ly + sideH1, lz + 1, lx, ly + sideH0, lz);
                }
                default -> { continue; }
            }

            float fnx = dir.getStepX(), fny = 0, fnz = dir.getStepZ();
            if (isWater) {
                boolean xAxis = (dir == Direction.EAST || dir == Direction.WEST);
                float sU0, sU1, sV0, sV1;
                if (xAxis) {
                    sU0 = wz / tileSize; sU1 = (wz + 1) / tileSize;
                } else {
                    sU0 = wx / tileSize; sU1 = (wx + 1) / tileSize;
                }
                sV0 = wy / tileSize; sV1 = (wy + 1) / tileSize;
                setUv4(tempUvs, sU0, sV1, sU1, sV1, sU1, sV0, sU0, sV0);
                writer.writeFluidQuad(tempPositions, tempUvs, fnx, fny, fnz, color,
                        null, 0, 0, 1, 1, layer, texManager);
            } else {
                // Vanilla FluidRenderer side mapping: one tile is half the flowing
                // sprite, so U spans its left half and V runs from the fluid surface
                // ((1 - height) / 2) down to the half-way line. The full sprite over a
                // one-block face showed the texture at twice the density.
                float sideU0 = frameU(flowSprite, 0.0f);
                float sideU1 = frameU(flowSprite, 0.5f);
                float sideVBottom = frameV(flowSprite, 0.5f);
                float sideVTop0 = frameV(flowSprite, (1.0f - sideH0) * 0.5f);
                float sideVTop1 = frameV(flowSprite, (1.0f - sideH1) * 0.5f);
                setUv4(tempUvs, sideU0, sideVBottom, sideU1, sideVBottom, sideU1, sideVTop1, sideU0, sideVTop0);
                writer.writeFluidQuad(tempPositions, tempUvs, fnx, fny, fnz, color,
                        flowId, fu0, fv0, fu1, fv1, layer, texManager);
            }
        }
    }

    static boolean isFluidTopCoveredByCeiling(BlockGetter world, float fluidHeight,
                                              BlockPos neighborPos, BlockState neighborState) {
        if (fluidHeight + WATER_HEIGHT_EPSILON < WATER_CEILING_CULL_MIN_HEIGHT
                || !neighborState.canOcclude()) {
            return false;
        }
        if (neighborState.isSolidRender(world, neighborPos)) {
            return true;
        }

        VoxelShape neighborShape = neighborState.getOcclusionShape(world, neighborPos);
        return isFluidSideCovered(Direction.UP, 1.0f, true, neighborShape);
    }

    static boolean isFluidTopCoveredByCeiling(float fluidHeight,
                                              boolean neighborOpaque, VoxelShape neighborShape) {
        if (fluidHeight + WATER_HEIGHT_EPSILON < WATER_CEILING_CULL_MIN_HEIGHT) return false;

        return isFluidSideCovered(Direction.UP, 1.0f, neighborOpaque, neighborShape);
    }

    static boolean isFluidSideCovered(Direction direction, float fluidHeight,
                                      boolean neighborOpaque, VoxelShape neighborShape) {
        if (!neighborOpaque) return false;

        VoxelShape fluidShape = Shapes.box(0.0, 0.0, 0.0, 1.0, fluidHeight, 1.0);
        return Shapes.blockOccudes(fluidShape, neighborShape, direction);
    }

    private FluidTopOcclusion.Exposure classifyWaterTopExposure(
            BlockAndTintGetter world, FluidState referenceFluid, int worldX, int worldY, int worldZ) {
        if (getCachedBlockState(world, worldX, worldY, worldZ).canOcclude()) {
            return FluidTopOcclusion.Exposure.EXPOSED;
        }

        if (waterTopCellCacheFluid == null
                || !waterTopCellCacheFluid.getType().isSame(referenceFluid.getType())) {
            Arrays.fill(waterTopCellCache, FluidTopOcclusion.UNKNOWN);
            waterTopCellCacheFluid = referenceFluid;
        }

        int originCacheIndex = waterTopCellCacheIndex(worldX, worldY, worldZ);
        if (originCacheIndex >= 0) {
            // The caller already established that this still-fluid origin has
            // a covering ceiling, so do not repeat its shape comparison.
            waterTopCellCache[originCacheIndex] = FluidTopOcclusion.CONNECTED;
        }

        waterTopCullWorld = world;
        waterTopCullReferenceFluid = referenceFluid;
        waterTopCullOriginX = worldX;
        waterTopCullOriginY = worldY;
        waterTopCullOriginZ = worldZ;
        try {
            return FluidTopOcclusion.classifyConnectedOrigin(
                    WATER_TOP_CULL_RADIUS, waterTopCullStack, waterTopCellClassifier);
        } finally {
            waterTopCullWorld = null;
            waterTopCullReferenceFluid = null;
        }
    }

    private byte classifyWaterTopCellAtOffset(int xOffset, int zOffset) {
        int worldX = waterTopCullOriginX + xOffset;
        int worldY = waterTopCullOriginY;
        int worldZ = waterTopCullOriginZ + zOffset;
        int cacheIndex = waterTopCellCacheIndex(worldX, worldY, worldZ);
        if (cacheIndex < 0) {
            return classifyWaterTopCell(
                    waterTopCullWorld, waterTopCullReferenceFluid, worldX, worldY, worldZ);
        }

        byte classification = waterTopCellCache[cacheIndex];
        if (classification == FluidTopOcclusion.UNKNOWN) {
            classification = classifyWaterTopCell(
                    waterTopCullWorld, waterTopCullReferenceFluid, worldX, worldY, worldZ);
            waterTopCellCache[cacheIndex] = classification;
        }
        return classification;
    }

    private int waterTopCellCacheIndex(int worldX, int worldY, int worldZ) {
        int x = worldX - baseX + WATER_TOP_CULL_RADIUS;
        int y = worldY - baseY;
        int z = worldZ - baseZ + WATER_TOP_CULL_RADIUS;
        if ((x | y | z) < 0
                || x >= WATER_TOP_CACHE_WIDTH
                || y >= SECTION_SIZE
                || z >= WATER_TOP_CACHE_WIDTH) {
            return -1;
        }
        return (y * WATER_TOP_CACHE_WIDTH + z) * WATER_TOP_CACHE_WIDTH + x;
    }

    private byte classifyWaterTopCell(BlockAndTintGetter world, FluidState referenceFluid,
                                      int worldX, int worldY, int worldZ) {
        BlockState state = getCachedBlockState(world, worldX, worldY, worldZ);
        if (state.canOcclude()) {
            return FluidTopOcclusion.BLOCKED;
        }

        FluidState fluid = getCachedFluidState(world, worldX, worldY, worldZ);
        if (!fluid.getType().isSame(referenceFluid.getType()) || !fluid.isSource()) {
            return FluidTopOcclusion.EXPOSED;
        }

        FluidState aboveFluid = getCachedFluidState(world, worldX, worldY + 1, worldZ);
        if (aboveFluid.getType().isSame(referenceFluid.getType())) {
            return FluidTopOcclusion.BLOCKED;
        }

        fluidCheckPos.set(worldX, worldY + 1, worldZ);
        BlockState aboveState = getCachedBlockState(world, worldX, worldY + 1, worldZ);
        return isFluidTopCoveredByCeiling(world, fluid.getOwnHeight(), fluidCheckPos, aboveState)
                ? FluidTopOcclusion.CONNECTED
                : FluidTopOcclusion.EXPOSED;
    }

    private float getFluidHeight(BlockAndTintGetter world, FluidState referenceFluid,
                                  int worldX, int worldY, int worldZ) {
        float totalHeight = 0;
        int count = 0;

        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                fluidCheckPos.set(worldX + dx, worldY + 1, worldZ + dz);
                FluidState aboveFluid = getCachedFluidState(world,
                        fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
                if (aboveFluid.getType().isSame(referenceFluid.getType())) {
                    return 1.0f;
                }

                fluidCheckPos.set(worldX + dx, worldY, worldZ + dz);
                FluidState fs = getCachedFluidState(world,
                        fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
                if (fs.getType().isSame(referenceFluid.getType())) {
                    float h = fs.getOwnHeight();
                    totalHeight += h;
                    count++;
                } else if (!getCachedBlockState(world,
                        fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ()).isSolid()) {
                    count++;
                }
            }
        }

        return count > 0 ? totalHeight / count : 0.5f;
    }

    private int getCachedWaterColor(IClientFluidTypeExtensions fluidType, BlockAndTintGetter world,
                                    FluidState fluidState, int lx, int ly, int lz) {
        int cell = ((ly >> 3) << 4) | ((lz >> 2) << 2) | (lx >> 2);
        int bit = 1 << cell;
        if ((waterColorKnownMask & bit) == 0) {
            waterColorCache[cell] = fluidType.getTintColor(fluidState, world, fluidPos);
            waterColorKnownMask |= bit;
        }
        return waterColorCache[cell];
    }

    // --- Helpers ---

    private boolean shouldDrawBlockSide(BlockAndTintGetter world, BlockState state, Direction side) {
        int neighborX = mutablePos.getX() + side.getStepX();
        int neighborY = mutablePos.getY() + side.getStepY();
        int neighborZ = mutablePos.getZ() + side.getStepZ();
        BlockState neighbor = getCachedBlockState(world, neighborX, neighborY, neighborZ);
        // Forge's position-dependent face hiding, in Embeddium's order: the neighbour's
        // answer first, because it defaults to false without a lookup, while
        // supportsExternalFaceHiding resolves the block's render types.
        neighborPos.set(neighborX, neighborY, neighborZ);
        if (neighbor.hidesNeighborFace(world, neighborPos, state, side.getOpposite())
                && state.supportsExternalFaceHiding()) {
            return false;
        }
        long key = ((long) Block.getId(state) << 35)
                | ((long) Block.getId(neighbor) << 3)
                | side.ordinal();
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - FACE_CULL_CACHE_BITS));
        if (faceCullKeys[slot] == key && faceCullResults[slot] != FACE_CULL_UNKNOWN) {
            return faceCullResults[slot] == FACE_CULL_DRAWN;
        }

        boolean drawn = Block.shouldRenderFace(state, world, mutablePos, side, neighborPos);
        faceCullKeys[slot] = key;
        faceCullResults[slot] = drawn ? FACE_CULL_DRAWN : FACE_CULL_HIDDEN;
        return drawn;
    }

    private BlockState getCachedBlockState(BlockGetter world, int worldX, int worldY, int worldZ) {
        int lx = worldX - baseX;
        int ly = worldY - baseY;
        int lz = worldZ - baseZ;
        if ((lx | ly | lz) < 0 || lx >= SECTION_SIZE || ly >= SECTION_SIZE || lz >= SECTION_SIZE) {
            neighborPos.set(worldX, worldY, worldZ);
            return world.getBlockState(neighborPos);
        }

        int idx = localIndex(lx, ly, lz);
        int word = idx >> 6;
        long bit = 1L << (idx & 63);
        if ((blockStateKnown[word] & bit) != 0) {
            return blockStateCache[idx];
        }

        neighborPos.set(worldX, worldY, worldZ);
        BlockState state = world.getBlockState(neighborPos);
        cacheLocalBlockState(idx, state);
        return state;
    }

    private FluidState getCachedFluidState(BlockGetter world, int worldX, int worldY, int worldZ) {
        int lx = worldX - baseX;
        int ly = worldY - baseY;
        int lz = worldZ - baseZ;
        if ((lx | ly | lz) < 0 || lx >= SECTION_SIZE || ly >= SECTION_SIZE || lz >= SECTION_SIZE) {
            neighborPos.set(worldX, worldY, worldZ);
            return world.getFluidState(neighborPos);
        }

        int idx = localIndex(lx, ly, lz);
        int word = idx >> 6;
        long bit = 1L << (idx & 63);
        if ((fluidStateKnown[word] & bit) != 0) {
            return fluidStateCache[idx];
        }

        FluidState fluidState = getCachedBlockState(world, worldX, worldY, worldZ).getFluidState();
        cacheLocalFluidState(idx, fluidState);
        return fluidState;
    }

    private void cacheLocalBlockState(int idx, BlockState state) {
        blockStateCache[idx] = state;
        blockStateKnown[idx >> 6] |= 1L << (idx & 63);
    }

    private void cacheLocalFluidState(int idx, FluidState fluidState) {
        fluidStateCache[idx] = fluidState;
        fluidStateKnown[idx >> 6] |= 1L << (idx & 63);
    }

    private static int localIndex(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }

    private int getBlockTint(BlockState state, BlockAndTintGetter world, int colorIndex) {
        if (colorIndex < 0 || colorIndex >= TINT_CACHE_SIZE) {
            return blockColors.getColor(state, world, mutablePos, colorIndex);
        }

        int bit = 1 << colorIndex;
        if ((tintValidMask & bit) == 0) {
            tintCache[colorIndex] = blockColors.getColor(state, world, mutablePos, colorIndex);
            tintValidMask |= bit;
        }
        return tintCache[colorIndex];
    }

    /**
     * The terrain layer a block's quads are bucketed under, by
     * {@link BlockTextureManager#layerType} name. A held block item asks the same
     * question, so it shades as its placed counterpart does.
     *
     * <p>Forge models choose their render types per model (a {@code render_type} in the
     * model JSON, or {@code getRenderTypes}), which the deprecated
     * {@link ItemBlockRenderTypes#getChunkRenderType} ignores, so this asks the block's
     * model. A model drawing several layers is classified by the first of them in chunk
     * layer order, its base layer; terrain classifies each layer separately through
     * {@link #classifyBlockLayer(BlockState, RenderType)}.
     */
    public static String classifyBlockLayer(BlockState state) {
        if (isClearGlass(state)) return "glass";
        Minecraft client = Minecraft.getInstance();
        if (client == null) return "solid";
        BakedModel model = client.getBlockRenderer().getBlockModel(state);
        SingleThreadedRandomSource layerRandom = LAYER_RANDOM.get();
        layerRandom.setSeed(42L);
        ChunkRenderTypeSet layers = model.getRenderTypes(state, layerRandom, ModelData.EMPTY);
        for (RenderType layer : layers) {
            return layerName(layer);
        }
        return "solid";
    }

    /**
     * The terrain layer for the quads one render type of a block's model draws, as
     * Embeddium hands that render type to its renderer.
     */
    public static String classifyBlockLayer(BlockState state, RenderType layer) {
        if (isClearGlass(state)) return "glass";
        return layerName(layer);
    }

    /**
     * Vanilla draws clear glass as a cutout: the texture is alpha 0 between
     * the frame pixels and the GL path simply discards those. Remix can
     * refract through it instead, so clear glass takes its own layer and a
     * translucent material with the frame as a diffuse layer.
     * Stained glass is already translucent and keeps vanilla's layer.
     */
    private static boolean isClearGlass(BlockState state) {
        return state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE);
    }

    private static String layerName(RenderType layer) {
        if (layer == RenderType.solid()) return "solid";
        if (layer == RenderType.cutoutMipped()) return "cutout_mipped";
        if (layer == RenderType.cutout()) return "cutout";
        if (layer == RenderType.translucent()) return "translucent";
        if (layer == RenderType.tripwire()) return "cutout";
        return "solid";
    }

    /**
     * Hands a sprite the section's geometry uses to the attached sink. Consecutive
     * repeats are dropped, which covers the common run of one sprite per block.
     */
    private void offerSprite(TextureAtlasSprite sprite) {
        Consumer<TextureAtlasSprite> sink = animatedSpriteSink;
        if (sink == null || sprite == lastSinkSprite) return;
        lastSinkSprite = sprite;
        sink.accept(sprite);
    }

    /**
     * A position inside the sprite as a fraction of its width, the 1.21
     * {@code getFrameU} the fluid mapping is written in; 1.20.1's
     * {@link TextureAtlasSprite#getU} takes sixteenths.
     */
    private static float frameU(TextureAtlasSprite sprite, float fraction) {
        return sprite.getU(fraction * 16.0f);
    }

    /** Vertical counterpart of {@link #frameU}. */
    private static float frameV(TextureAtlasSprite sprite, float fraction) {
        return sprite.getV(fraction * 16.0f);
    }

    private static void setPos4(float[] out,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float x3, float y3, float z3) {
        out[0] = x0; out[1] = y0; out[2] = z0;
        out[3] = x1; out[4] = y1; out[5] = z1;
        out[6] = x2; out[7] = y2; out[8] = z2;
        out[9] = x3; out[10] = y3; out[11] = z3;
    }

    private static void setUv4(float[] out,
                                float u0, float v0, float u1, float v1,
                                float u2, float v2, float u3, float v3) {
        out[0] = u0; out[1] = v0; out[2] = u1; out[3] = v1;
        out[4] = u2; out[5] = v2; out[6] = u3; out[7] = v3;
    }
}

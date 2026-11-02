/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

/**
 * Section lifecycle hooks on Embeddium's {@code RenderSectionManager}, the owner of every
 * terrain section under Embeddium.
 *
 * <ul>
 *   <li><b>updateSectionInfo:</b> Embeddium settles an all-air section with
 *       {@code BuiltSectionInfo.EMPTY} and no meshing task, both when a rebuild finds the
 *       section emptied and when a column becomes ready with the section already empty.
 *       No capture will replace Remix terrain there, so the section is handed to
 *       {@link ChunkManager#onSectionSettledEmpty} for validated removal.</li>
 *   <li><b>updateChunks:</b> feeds the Remix recapture queue into Embeddium's rebuild
 *       lists. Embeddium otherwise builds only the sections its occlusion walk reaches,
 *       while the Remix scene also holds terrain behind the camera.</li>
 * </ul>
 *
 * <p>A section Embeddium removes ({@code onSectionRemoved}, from its readiness tracking)
 * keeps its Remix terrain, as a vanilla slot reuse did: Remix terrain follows the loaded
 * world, not Embeddium's renderable set. Column unloads reach {@link ChunkManager} through
 * Forge's client {@code ChunkEvent.Unload}.
 *
 * <p>Embeddium's methods and fields are not obfuscated, so everything here is
 * {@code remap = false}. Render thread only, like every caller of these methods.
 */
@Mixin(RenderSectionManager.class)
public abstract class EmbeddiumRenderSectionManagerMixin implements WorldRendererTerrainAccess {

    /** Recapture entries offered to one set of Embeddium's rebuild lists, nearest first. */
    @Unique
    private static final int RTXREMIX$MAX_OFFERED_PER_FRAME = 256;

    @Shadow(remap = false)
    @Final
    private Long2ReferenceMap<RenderSection> sectionByPosition;

    @Shadow(remap = false)
    @Final
    private ClientLevel world;

    @Shadow(remap = false)
    private Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuildLists;

    @Shadow(remap = false)
    public abstract void scheduleRebuild(int x, int y, int z, boolean important);

    /**
     * Sections queued for Remix recapture that Embeddium has not submitted yet, nearest the
     * camera first at the time they were queued.
     */
    @Unique
    private final ArrayDeque<RenderSection> rtxremix$recaptureQueue = new ArrayDeque<>();

    /**
     * The rebuild-list map the recapture queue was last offered to. Embeddium replaces the
     * map whenever it rebuilds its render lists, which discards what was offered, so the
     * queue is offered again only then or when new sections join it.
     */
    @Unique
    private Map<ChunkUpdateType, ArrayDeque<RenderSection>> rtxremix$offeredLists;

    @Inject(method = "updateSectionInfo", at = @At("HEAD"), remap = false)
    private void rtxremix$onSectionInfo(RenderSection render, BuiltSectionInfo info, CallbackInfo ci) {
        // Identity, not emptiness: a meshing task always builds a fresh info object, and
        // while capturing that object is geometry-free too. EMPTY marks exactly the
        // sections Embeddium settled without a task.
        if (info != BuiltSectionInfo.EMPTY) {
            return;
        }
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod != null ? mod.getRenderer() : null;
        ChunkManager chunkManager = renderer != null ? renderer.getChunkManager() : null;
        if (chunkManager != null) {
            chunkManager.onSectionSettledEmpty(render.getChunkX(), render.getChunkY(), render.getChunkZ());
        }
    }

    /**
     * Offers the recapture queue to the rebuild lists {@code updateChunks} is about to
     * submit from. Entries go behind Embeddium's own, so the visible sections build first,
     * and they share its scheduling budget. A section with a build in flight is held back,
     * exactly as Embeddium's own collector holds it back, so one section never has two
     * builds racing. An entry leaves the queue once Embeddium has submitted it (its pending
     * update is cleared) or disposed of it.
     */
    @Inject(method = "updateChunks", at = @At("HEAD"), remap = false)
    private void rtxremix$offerRecaptures(boolean updateImmediately, CallbackInfo ci) {
        ArrayDeque<RenderSection> queue = rtxremix$recaptureQueue;
        if (queue.isEmpty()) {
            return;
        }
        Map<ChunkUpdateType, ArrayDeque<RenderSection>> lists = this.rebuildLists;
        if (lists == rtxremix$offeredLists) {
            // Still holds what was offered; Embeddium submits from it under its budget.
            return;
        }
        rtxremix$offeredLists = lists;

        // Only the head is offered: Embeddium submits a few tasks per frame, and every
        // completed build replaces the lists, which offers the next entries.
        int remaining = queue.size();
        int offered = 0;
        while (remaining-- > 0) {
            RenderSection section = queue.pollFirst();
            ChunkUpdateType pending = section.getPendingUpdate();
            if (section.isDisposed() || pending == null || pending.isSort()) {
                continue;
            }
            queue.addLast(section);
            if (offered < RTXREMIX$MAX_OFFERED_PER_FRAME
                    && section.getBuildCancellationToken() == null) {
                ArrayDeque<RenderSection> list = lists.get(pending);
                if (list != null) {
                    list.add(section);
                    offered++;
                }
            }
        }
    }

    @Override
    public LongSet rtxremix$builtSectionPositions() {
        LongOpenHashSet built = new LongOpenHashSet();
        for (RenderSection section : sectionByPosition.values()) {
            // Built through a task (or settled empty by one) since this manager added it;
            // a section Embeddium settled as empty on arrival never was.
            if (section.getLastBuiltFrame() < 0) {
                continue;
            }
            built.add(SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
        }
        return built;
    }

    @Override
    public LongSet rtxremix$queueSectionsForRemix(LongSet sections) {
        if (sections.isEmpty() || world == null) {
            return LongSets.emptySet();
        }

        // A section exists in this manager only while its column and all eight neighbours
        // have block and light data (Embeddium's readiness), which is the same gate vanilla
        // applies before building: a section built before its light is ready bakes bad light.
        ArrayList<RenderSection> loaded = new ArrayList<>();
        LongIterator positions = sections.iterator();
        while (positions.hasNext()) {
            RenderSection section = sectionByPosition.get(positions.nextLong());
            if (section == null || section.isDisposed() || rtxremix$isLiveSectionEmpty(section)) {
                continue;
            }
            loaded.add(section);
        }

        // Nearest first, so visible holes close promptly while the Remix scene repopulates.
        // The manager's own camera position is stale until its first update after a reload,
        // so the game camera decides.
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float cameraX = (float) camera.x;
        float cameraY = (float) camera.y;
        float cameraZ = (float) camera.z;
        loaded.sort(Comparator.comparingDouble(
                section -> section.getSquaredDistance(cameraX, cameraY, cameraZ)));

        LongOpenHashSet queued = new LongOpenHashSet(loaded.size());
        for (RenderSection section : loaded) {
            rtxremix$queueRecapture(section);
            queued.add(SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
        }
        return queued;
    }

    @Override
    public void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz) {
        RenderSection section = sectionByPosition.get(SectionPos.asLong(cx, sy, cz));
        if (section == null || section.isDisposed()) {
            return;
        }
        rtxremix$queueRecapture(section);
    }

    /**
     * Gives {@code section} a pending rebuild if it has none (a freshly added section
     * already waits for its initial build) and adds it to the recapture queue.
     */
    @Unique
    private void rtxremix$queueRecapture(RenderSection section) {
        ChunkUpdateType pending = section.getPendingUpdate();
        if (pending == null || pending.isSort()) {
            scheduleRebuild(section.getChunkX(), section.getChunkY(), section.getChunkZ(), false);
        }
        rtxremix$recaptureQueue.addLast(section);
        rtxremix$offeredLists = null;
    }

    /** Embeddium's own emptiness test from {@code WorldSlice.prepare}. */
    @Unique
    private boolean rtxremix$isLiveSectionEmpty(RenderSection section) {
        LevelChunk chunk = world.getChunk(section.getChunkX(), section.getChunkZ());
        LevelChunkSection[] chunkSections = chunk.getSections();
        int index = world.getSectionIndexFromSectionY(section.getChunkY());
        if (index < 0 || index >= chunkSections.length) {
            return true;
        }
        LevelChunkSection chunkSection = chunkSections[index];
        return chunkSection == null || chunkSection.hasOnlyAir();
    }
}

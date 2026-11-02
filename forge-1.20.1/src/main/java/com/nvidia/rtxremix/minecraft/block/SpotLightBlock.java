/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import com.nvidia.rtxremix.minecraft.light.OverridesItemLight;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base for aimable light sources, the lamp room of a lighthouse.
 *
 * <p>Emits a lens-sized Remix disk light with cone shaping, i.e. a
 * <em>spot</em> light.
 *
 * <p>Concrete subclasses differ only in registry id, lens texture, and the
 * colour of the Remix spot light they emit, mirroring how vanilla's
 * copper bulbs (1.21) hold the shared behaviour while each copper-oxidation
 * block supplies its own settings.
 *
 * <p>Carries the vanilla {@link net.minecraft.world.level.block.state.properties.BlockStateProperties#FACING}
 * property (all six directions), set to the opposite of the placing player's
 * look direction so the lens faces them, matching vanilla dispensers and
 * observers.
 *
 * <p>Using {@code BlockStateProperties.FACING} rather than a custom property is
 * deliberate: {@code BlockLightRuleManager}'s {@code StateVectorSpec} reads that
 * exact property for its {@code "facing"}, {@code "facing_opposite"}, and
 * {@code "facing_axis"} vector sources, so a block light rule can aim a Remix
 * light along this block's facing with no additional code.
 *
 * <p>Implements {@link OverridesItemLight}, which suppresses the <em>generic</em>
 * item light {@code LightManager} would otherwise attach to any light-emitting
 * {@code BlockItem}: a beam at world radiance, held at arm's length, would white
 * the whole view out. A held beacon is not dark, though: it gets a dedicated
 * beam from {@code LightManager}, sharing this block's base cone shape but scaled
 * by {@code BeaconBeam#HELD_INTENSITY_SCALE}. The problem was the radiance, not
 * the beam, so it is solved by scaling rather than exclusion.
 * A <em>dropped</em> beacon does stay dark, an item entity having no aim worth
 * pointing a beam along.
 */
public abstract class SpotLightBlock extends DirectionalBlock implements OverridesItemLight {

    /** Full block light level, matching a lantern. */
    protected static final int LUMINANCE = 15;

    /** Every beacon variant's block, registered by {@link #registerAll}. */
    static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, RtxRemixCommonMod.MOD_ID);

    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, RtxRemixCommonMod.MOD_ID);

    /**
     * Block items that go into vanilla's "Functional Blocks" creative tab, where
     * torches and lanterns live. {@link SpotLightCreativeTab} adds them.
     */
    private static final List<RegistryObject<Item>> CREATIVE_TAB_ITEMS = new ArrayList<>();

    private final LightEntry.ColorOverride beamColor;

    protected SpotLightBlock(BlockBehaviour.Properties settings, LightEntry.ColorOverride beamColor) {
        super(settings);
        this.beamColor = beamColor;
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    /** Shared settings for every beacon variant. */
    protected static BlockBehaviour.Properties beaconSettings() {
        return BlockBehaviour.Properties.of()
                .strength(3.0f)
                .sound(SoundType.METAL)
                .lightLevel(state -> LUMINANCE);
    }

    /**
     * Colour of the Remix spot light this variant emits. Read by
     * {@code BlockLightDefaults} so the light rule does not have to enumerate
     * variants.
     */
    public LightEntry.ColorOverride beamColor() {
        return beamColor;
    }

    /**
     * Registers every beacon variant, its block item, and its block entity type
     * on the mod event bus. Called from the common mod constructor, not the
     * client one, because blocks live in a synced registry. Everything this
     * method touches therefore has to be loadable on a dedicated server.
     */
    public static void registerAll(IEventBus modBus) {
        registerItem(CopperBeaconBlock.ID, CopperBeaconBlock.INSTANCE);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        CopperBeaconBlockEntity.register(modBus);
    }

    /** The block items to add to the "Functional Blocks" creative tab. */
    static List<RegistryObject<Item>> creativeTabItems() {
        return Collections.unmodifiableList(CREATIVE_TAB_ITEMS);
    }

    /** Registers one variant's item and queues its creative tab entry. */
    private static void registerItem(String path, RegistryObject<? extends SpotLightBlock> block) {
        RegistryObject<Item> item =
                ITEMS.register(path, () -> new BlockItem(block.get(), new Item.Properties()));
        CREATIVE_TAB_ITEMS.add(item);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getNearestLookingDirection().getOpposite());
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }
}

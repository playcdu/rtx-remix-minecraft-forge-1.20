/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * One validated control edit from an open Copper Beacon screen.
 *
 * <p>This class also owns the mod's Forge {@link SimpleChannel}, the
 * counterpart of Fabric's payload registry. The channel accepts a peer that
 * lacks it ({@link NetworkRegistry#acceptMissingOr}) on both sides, because the
 * renderer is a client mod first: a client with the mod must be able to join a
 * server without it, and a server with it must accept vanilla-channel clients.
 * Against such a peer the beacon screen never opens remotely, and
 * {@link #canSend} keeps a locally open one from sending.
 */
public record CopperBeaconUpdatePayload(int syncId, Field field, float value) {

    /** Bumped whenever a message on {@link #CHANNEL} changes its wire format. */
    public static final String PROTOCOL_VERSION = "1";

    public static final ResourceLocation CHANNEL_ID =
            new ResourceLocation(RtxRemixCommonMod.MOD_ID, "main");

    private static final int MESSAGE_ID = 0;

    private static SimpleChannel channel;

    /**
     * Creates the channel and registers the update message. Call once from the
     * common mod constructor, on both sides, before Forge locks the network
     * registry.
     */
    public static void register() {
        if (channel != null) {
            return;
        }
        SimpleChannel created = NetworkRegistry.newSimpleChannel(
                CHANNEL_ID,
                () -> PROTOCOL_VERSION,
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION));
        created.registerMessage(
                MESSAGE_ID,
                CopperBeaconUpdatePayload.class,
                CopperBeaconUpdatePayload::encode,
                CopperBeaconUpdatePayload::decode,
                CopperBeaconUpdatePayload::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel = created;
    }

    /** The mod's network channel, or null before {@link #register()} ran. */
    public static SimpleChannel channel() {
        return channel;
    }

    /**
     * Whether the server at the other end of {@code connection} speaks this
     * channel. False on a server without the mod.
     */
    public static boolean canSend(Connection connection) {
        return channel != null && connection != null && channel.isRemotePresent(connection);
    }

    /** Sends {@code payload} to the server. Client side only. */
    public static void send(CopperBeaconUpdatePayload payload) {
        if (channel != null) {
            channel.sendToServer(payload);
        }
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(syncId);
        buffer.writeVarInt(field.ordinal());
        buffer.writeFloat(value);
    }

    public static CopperBeaconUpdatePayload decode(FriendlyByteBuf buffer) {
        return new CopperBeaconUpdatePayload(
                buffer.readVarInt(),
                Field.byId(buffer.readVarInt()),
                buffer.readFloat());
    }

    /**
     * Applies the edit on the server thread, only to the sender's open beacon
     * menu with the matching sync id, and only while that menu is still valid.
     * Each setter clamps and sanitizes its value.
     */
    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            context.enqueueWork(() -> {
                if (player.containerMenu instanceof CopperBeaconScreenHandler handler
                        && handler.containerId == syncId
                        && handler.stillValid(player)) {
                    handler.applyUpdate(field, value);
                }
            });
        }
        context.setPacketHandled(true);
    }

    public enum Field {
        INTENSITY,
        CONE_ANGLE,
        REDSTONE_CONTROLLED,
        YAW_POSITION,
        YAW_MODE,
        YAW_ROTATION_SPEED,
        YAW_MINIMUM_OFFSET,
        YAW_MAXIMUM_OFFSET,
        YAW_CYCLE_SECONDS,
        YAW_PATROL_SECONDS,
        PITCH_POSITION,
        PITCH_MODE,
        PITCH_ROTATION_SPEED,
        PITCH_MINIMUM_OFFSET,
        PITCH_MAXIMUM_OFFSET,
        PITCH_CYCLE_SECONDS,
        PITCH_PATROL_SECONDS,
        SATURATION;

        private static Field byId(int id) {
            Field[] values = values();
            return id >= 0 && id < values.length ? values[id] : INTENSITY;
        }
    }
}

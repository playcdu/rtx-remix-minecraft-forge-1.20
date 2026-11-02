/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraftforge.common.ForgeConfig;
import net.minecraftforge.common.ForgeConfigSpec;

import java.lang.reflect.Field;

/**
 * Brings up the parts of Minecraft and Forge that some tests touch, in a plain
 * JUnit JVM with no mod loader.
 *
 * <p>1.20.1 keeps the root registry name on {@code BuiltInRegistries}, so even
 * creating a registry key, such as a biome key, initializes every built-in
 * registry, and those refuse to exist before {@link Bootstrap#bootStrap()}.
 */
public final class MinecraftTestBootstrap {

    private MinecraftTestBootstrap() {}

    /**
     * Runs Minecraft's bootstrap once, so vanilla's built-in registries are filled.
     *
     * <p>Forge's bootstrap ends with {@code NetworkHooks.init()}, which builds the
     * handshake channel on an event bus that only works under ModLauncher's class
     * transformer. Outside the game that last step fails after every vanilla
     * registry is filled, so that one failure is expected, and the registries it
     * leaves behind are checked instead.
     */
    public static synchronized void registries() {
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (ExceptionInInitializerError error) {
            if (!thrownByForgeNetworkInit(error)) {
                throw error;
            }
        }
        if (BuiltInRegistries.BLOCK.size() == 0 || BuiltInRegistries.ITEM.size() == 0) {
            throw new IllegalStateException("Minecraft's bootstrap left the built-in registries empty");
        }
    }

    /**
     * Loads Forge's client config with its defaults, which FML would read from
     * {@code forge-client.toml} in game. Forge's patched vanilla code, such as
     * {@code ForgeHooksClient.getNearestStable} in the block-crack consumer, reads it.
     */
    public static synchronized void forgeClientConfigDefaults() {
        registries();
        ForgeConfigSpec spec = forgeClientSpec();
        if (!spec.isLoaded()) {
            CommentedConfig config = CommentedConfig.inMemory();
            spec.correct(config);
            spec.setConfig(config);
        }
    }

    private static boolean thrownByForgeNetworkInit(Throwable error) {
        for (StackTraceElement frame : error.getStackTrace()) {
            if (frame.getClassName().equals("net.minecraftforge.network.NetworkHooks")
                    && frame.getMethodName().equals("init")) {
                return true;
            }
        }
        return false;
    }

    /** The spec is package-private on {@link ForgeConfig}; FML reaches it through the mod container. */
    private static ForgeConfigSpec forgeClientSpec() {
        try {
            Field field = ForgeConfig.class.getDeclaredField("clientSpec");
            field.setAccessible(true);
            return (ForgeConfigSpec) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Forge's client config spec moved", e);
        }
    }
}

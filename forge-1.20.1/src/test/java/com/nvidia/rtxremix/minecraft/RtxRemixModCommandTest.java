/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtxRemixModCommandTest {

    @Test
    void cameraPlaySupportsDefaultTimeAndExplicitClockModes() {
        CommandDispatcher<CommandSourceStack> dispatcher = cameraDispatcher();

        assertParses(dispatcher, "camera play demo");
        assertParses(dispatcher, "camera play demo time");
        assertParses(dispatcher, "camera play demo fixed-60");

        ParseResults<CommandSourceStack> invalid =
                dispatcher.parse("camera play demo bogus", null);
        assertTrue(invalid.getReader().canRead() || !invalid.getExceptions().isEmpty());
    }

    @Test
    void cameraPlaySuggestsOnlySupportedClockModes() {
        CommandDispatcher<CommandSourceStack> dispatcher = cameraDispatcher();
        ParseResults<CommandSourceStack> parsed =
                dispatcher.parse("camera play demo ", null);

        Set<String> suggestions = dispatcher.getCompletionSuggestions(parsed).join()
                .getList()
                .stream()
                .map(suggestion -> suggestion.getText())
                .collect(Collectors.toSet());

        assertEquals(Set.of("time", "fixed-60"), suggestions);
    }

    @Test
    void omittedModeRemainsExecutableAtCameraNameNode() {
        CommandDispatcher<CommandSourceStack> dispatcher = cameraDispatcher();
        var nameNode = dispatcher.getRoot()
                .getChild("camera")
                .getChild("play")
                .getChild("name");

        assertNotNull(nameNode);
        assertNotNull(nameNode.getCommand());
        assertNotNull(nameNode.getChild("time").getCommand());
        assertNotNull(nameNode.getChild("fixed-60").getCommand());
    }

    private static CommandDispatcher<CommandSourceStack> cameraDispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(RtxRemixMod.cameraCommand());
        return dispatcher;
    }

    private static void assertParses(
            CommandDispatcher<CommandSourceStack> dispatcher,
            String command
    ) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, null);
        assertFalse(parsed.getReader().canRead(), command);
        assertTrue(parsed.getExceptions().isEmpty(), command);
    }
}

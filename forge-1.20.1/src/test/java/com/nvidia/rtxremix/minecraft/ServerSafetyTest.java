/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the client/server split of the Forge entry class.
 *
 * <p>Blocks live in a synced registry, so {@link RtxRemixForgeMod},
 * {@link RtxRemixCommonMod}, and everything they reach are loaded on dedicated
 * servers, where {@code net.minecraft.client.*} does not exist. So is every
 * {@code @Mod.EventBusSubscriber} that is not limited to {@link Dist#CLIENT},
 * because FML loads those on every dist. A single client reference anywhere on
 * that path turns into a {@code NoClassDefFoundError} at registration time on a
 * server, which is the one environment none of the other tests and neither
 * {@code runClient} nor a compile-only check exercise.
 *
 * <p>The client entrypoint {@link RtxRemixMod} is wired through one holder
 * class, {@link #CLIENT_HOLDER}, which only the client dist calls. The walk
 * below stops there, and nothing else on the path may name the entrypoint.
 *
 * <p>These tests read the compiled class files and scan the constant pool
 * directly. That is deliberately cruder than reflection: it catches references
 * in field and method <em>descriptors</em>, not just ones that happen to be
 * resolved at class-init, and it needs no Minecraft classes on the test
 * classpath.
 */
class ServerSafetyTest {

    private static final String MOD_PACKAGE = "com/nvidia/rtxremix/minecraft/";

    /** The one class on the common path that may name the client entrypoint. */
    private static final String CLIENT_HOLDER = MOD_PACKAGE + "RtxRemixForgeMod$ClientEntry";

    private static final String CLIENT_ENTRYPOINT = MOD_PACKAGE + "RtxRemixMod";

    /** Where FML starts constructing the mod on every dist. */
    private static final List<String> COMMON_ROOTS = List.of(
            MOD_PACKAGE + "RtxRemixForgeMod",
            MOD_PACKAGE + "RtxRemixCommonMod");

    /**
     * Server-side content that must stay server-safe whether or not the walk
     * reaches it today. The walk below covers what the entry class actually
     * reaches; this list keeps the beacon's shared state honest too.
     *
     * <p>{@code LightEntry$ColorOverride} is on the list because the beacon
     * blocks hold a beam colour; note that referencing a nested type does
     * <em>not</em> load its outer class, so {@code LightEntry} itself is listed
     * separately as cheap insurance rather than out of necessity.
     */
    private static final String[] SERVER_PATH_CLASSES = {
            "com.nvidia.rtxremix.minecraft.RtxRemixForgeMod",
            "com.nvidia.rtxremix.minecraft.RtxRemixCommonMod",
            "com.nvidia.rtxremix.minecraft.block.SpotLightBlock",
            "com.nvidia.rtxremix.minecraft.block.SpotLightCreativeTab",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconAnimationMode",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconAxisState",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconPose",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenData",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenHandler",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconUpdatePayload",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconShapes",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconState",
            "com.nvidia.rtxremix.minecraft.light.OverridesItemLight",
            "com.nvidia.rtxremix.minecraft.light.LightEntry$ColorOverride",
            "com.nvidia.rtxremix.minecraft.light.LightEntry",
            "com.nvidia.rtxremix.minecraft.particle.rapids.RapidFoamParticles",
    };

    private static final Pattern MOD_CLASS_REFERENCE =
            Pattern.compile(Pattern.quote(MOD_PACKAGE) + "[A-Za-z0-9_/$]+");

    private static final String EVENT_BUS_SUBSCRIBER =
            "L" + Mod.EventBusSubscriber.class.getName().replace('.', '/') + ";";

    @Test
    void serverPathClassesReferenceNoClientClasses() {
        for (String className : serverPath()) {
            assertFalse(referencesText(className, "net/minecraft/client"),
                    className + " references a net.minecraft.client class. It is loaded on "
                            + "dedicated servers, where those classes do not exist.");
        }
    }

    /**
     * {@code RtxRemixMod} is the client entrypoint and cannot be loaded on a
     * dedicated server, so nothing on the registration path may name it; see
     * {@link RtxRemixCommonMod#MOD_ID} for the constant this used to pull in.
     */
    @Test
    void serverPathClassesDoNotReferenceTheClientEntrypoint() {
        for (String className : serverPath()) {
            assertFalse(referencesText(className, "minecraft/RtxRemixMod"),
                    className + " references RtxRemixMod, the client entrypoint. Move whatever it "
                            + "needs onto RtxRemixCommonMod, or reach the client through "
                            + CLIENT_HOLDER + "; a static final String only survives today "
                            + "because javac inlines compile-time constants.");
        }
    }

    /**
     * The walk stops at {@link #CLIENT_HOLDER}, so that holder must exist and
     * be the class that names the client entrypoint; otherwise the walk skips
     * a class it should have followed and the assertions above are vacuous.
     */
    @Test
    void clientEntrypointIsReachedOnlyThroughItsHolder() {
        assertTrue(referencesText(CLIENT_HOLDER.replace('/', '.'), CLIENT_ENTRYPOINT),
                CLIENT_HOLDER + " no longer wires " + CLIENT_ENTRYPOINT
                        + "; update CLIENT_HOLDER to the class that does.");
        assertTrue(referencesText(COMMON_ROOTS.get(0).replace('/', '.'), CLIENT_HOLDER),
                "the Forge entry class no longer calls " + CLIENT_HOLDER);
        assertFalse(serverPath().contains(CLIENT_HOLDER.replace('/', '.')));
    }

    /**
     * Sanity check: the walk follows the registration calls out of the entry
     * class and picks up the subscribers FML loads on every dist.
     */
    @Test
    void serverPathFollowsRegistrationAndCommonSubscribers() {
        Set<String> path = serverPath();
        for (String expected : List.of(
                "com.nvidia.rtxremix.minecraft.block.SpotLightBlock",
                "com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock",
                "com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity",
                "com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenHandler",
                "com.nvidia.rtxremix.minecraft.block.CopperBeaconUpdatePayload",
                "com.nvidia.rtxremix.minecraft.block.SpotLightCreativeTab",
                "com.nvidia.rtxremix.minecraft.light.LightEntry$ColorOverride",
                "com.nvidia.rtxremix.minecraft.particle.rapids.RapidFoamParticles")) {
            assertTrue(path.contains(expected), "the server path walk did not reach " + expected);
        }
        assertFalse(path.contains("com.nvidia.rtxremix.minecraft.light.BlockLightRuleRemapListener"),
                "a Dist.CLIENT subscriber is not loaded on a dedicated server");
    }

    /** Sanity check: the scan finds a reference that is genuinely present. */
    @Test
    void referencesTextDetectsAKnownReference() {
        assertTrue(referencesText("com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock",
                        "minecraft/block/SpotLightBlock"),
                "CopperBeaconBlock extends SpotLightBlock, so the scan should see it; "
                        + "if this fails the other assertions in this class are vacuous.");
    }

    /**
     * Every mod class a dedicated server can load: the explicit list, plus the
     * transitive constant-pool closure of the common roots and the common
     * subscribers, stopping at {@link #CLIENT_HOLDER}. As binary class names.
     */
    private static Set<String> serverPath() {
        Set<String> visited = new TreeSet<>();
        Deque<String> pending = new ArrayDeque<>(COMMON_ROOTS);
        pending.addAll(commonEventBusSubscribers());
        while (!pending.isEmpty()) {
            String internalName = pending.pop();
            if (internalName.equals(CLIENT_HOLDER) || !visited.add(internalName)) {
                continue;
            }
            for (String constant : utf8Constants(readClassBytes(internalName.replace('/', '.')))) {
                Matcher matcher = MOD_CLASS_REFERENCE.matcher(constant);
                while (matcher.find()) {
                    pending.push(matcher.group());
                }
            }
        }
        Set<String> classes = new TreeSet<>();
        visited.forEach(internalName -> classes.add(internalName.replace('/', '.')));
        classes.addAll(Arrays.asList(SERVER_PATH_CLASSES));
        return classes;
    }

    /**
     * Subscribers FML registers on a dedicated server: every one not limited to
     * the client. The annotation is read from the class file, because reflecting
     * on it initializes FML's bus enum, which needs a running mod loader.
     */
    private static List<String> commonEventBusSubscribers() {
        List<String> subscribers = new ArrayList<>();
        for (String internalName : modClasses()) {
            ClassNode node = new ClassNode();
            new ClassReader(readClassBytes(internalName.replace('/', '.')))
                    .accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (node.visibleAnnotations == null) {
                continue;
            }
            for (AnnotationNode annotation : node.visibleAnnotations) {
                if (annotation.desc.equals(EVENT_BUS_SUBSCRIBER) && loadsOnDedicatedServer(annotation)) {
                    subscribers.add(internalName);
                }
            }
        }
        assertFalse(subscribers.isEmpty(), "found no common @Mod.EventBusSubscriber; the scan is broken");
        return subscribers;
    }

    /** {@code value} lists the dists the subscriber loads on, both when omitted. */
    private static boolean loadsOnDedicatedServer(AnnotationNode subscriber) {
        if (subscriber.values == null) {
            return true;
        }
        for (int i = 0; i < subscriber.values.size(); i += 2) {
            if (!"value".equals(subscriber.values.get(i))) {
                continue;
            }
            for (Object dist : (List<?>) subscriber.values.get(i + 1)) {
                if (Dist.DEDICATED_SERVER.name().equals(((String[]) dist)[1])) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /** Internal names of every compiled class in the mod's packages. */
    private static List<String> modClasses() {
        Path classesRoot;
        try {
            classesRoot = Path.of(RtxRemixCommonMod.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
        Path modRoot = classesRoot.resolve(MOD_PACKAGE);
        try (Stream<Path> files = Files.walk(modRoot)) {
            return files.filter(file -> file.toString().endsWith(".class"))
                    .map(file -> classesRoot.relativize(file).toString().replace('\\', '/'))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new AssertionError("Failed listing " + modRoot, e);
        }
    }

    /**
     * True if the compiled form of {@code className} contains {@code needle}
     * anywhere: constant pool entries store class and descriptor names as raw
     * modified-UTF8, so a search over the constant pool strings is sufficient.
     */
    private static boolean referencesText(String className, String needle) {
        for (String constant : utf8Constants(readClassBytes(className))) {
            if (constant.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** The {@code CONSTANT_Utf8} entries of a class file's constant pool. */
    private static List<String> utf8Constants(byte[] classBytes) {
        List<String> constants = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            assertEquals(0xCAFEBABE, in.readInt(), "not a class file");
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            for (int index = 1; index < count; index++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> constants.add(in.readUTF());
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        index++;
                    }
                    case 7, 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> in.readInt();
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new AssertionError("unknown constant pool tag " + tag);
                }
            }
        } catch (IOException e) {
            throw new AssertionError("Failed reading a constant pool", e);
        }
        return constants;
    }

    private static byte[] readClassBytes(String className) {
        String resource = "/" + className.replace('.', '/') + ".class";
        try (InputStream in = ServerSafetyTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "Could not find compiled class " + className
                    + ". If it was renamed or removed, update SERVER_PATH_CLASSES.");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new AssertionError("Failed reading " + resource, e);
        }
    }
}

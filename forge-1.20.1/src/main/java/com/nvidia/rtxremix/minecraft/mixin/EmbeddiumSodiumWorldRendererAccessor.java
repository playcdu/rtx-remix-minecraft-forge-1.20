/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the section manager Embeddium keeps private in its world renderer. It is
 * null while no world is loaded, and replaced by every {@code SodiumWorldRenderer.reload}
 * (a {@code LevelRenderer.allChanged}, a render-distance change), so callers read it
 * fresh rather than keep it. Embeddium's fields are not obfuscated: {@code remap = false}.
 */
@Mixin(SodiumWorldRenderer.class)
public interface EmbeddiumSodiumWorldRendererAccessor {

    @Accessor(value = "renderSectionManager", remap = false)
    RenderSectionManager rtxremix$getRenderSectionManager();
}

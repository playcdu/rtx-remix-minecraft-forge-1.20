/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

/**
 * Reads the texture a {@link RenderStateShard.TextureStateShard} binds, which 1.20.1
 * keeps in a private field behind the protected {@code cutoutTexture()}.
 */
@Mixin(RenderStateShard.TextureStateShard.class)
public interface EntityTextureStateShardAccessor {

    @Accessor("texture")
    Optional<ResourceLocation> rtxremix$getTexture();
}

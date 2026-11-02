/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads a composite layer's render-state shards for {@code RenderLayerUtils}.
 *
 * <p>{@code RenderType.CompositeRenderType} is package-private in 1.20.1, so the
 * capture cannot name it; a layer is recognised by this interface instead
 * ({@code layer instanceof EntityCompositeRenderTypeAccessor}).
 */
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface EntityCompositeRenderTypeAccessor {

    @Accessor("state")
    RenderType.CompositeState rtxremix$getState();
}

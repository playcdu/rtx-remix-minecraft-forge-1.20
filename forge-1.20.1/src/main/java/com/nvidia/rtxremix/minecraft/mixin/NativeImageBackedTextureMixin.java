/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.DynamicTextureCache;
import net.minecraft.client.renderer.texture.AbstractTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@link DynamicTexture#upload()} so the pixels of dynamic textures (maps,
 * capes, etc.) reach {@link DynamicTextureCache}.
 *
 * <p>At the HEAD of {@code upload()} the image may be null: 1.20.1 logs "Trying to
 * upload disposed texture" and uploads nothing, so this skips that case too. Otherwise
 * it tags the texture's current image, which {@code setPixels} may have swapped since
 * registration, as the texture's upload source; the snapshot itself is taken where the
 * image meets GL, in {@code NativeImageUploadMixin}, so a whole-texture upload and a
 * sub-rectangle upload made straight from the image are captured the same way.
 *
 * <p>Only textures that {@code TextureManagerMixin} registered for tracking are tagged;
 * all others are ignored with minimal overhead.
 */
@Mixin(DynamicTexture.class)
public class NativeImageBackedTextureMixin {

    @Shadow
    @Nullable
    private NativeImage pixels;

    @Inject(method = "upload", at = @At("HEAD"))
    private void rtxremix$onUpload(CallbackInfo ci) {
        if (pixels == null) return;
        DynamicTextureCache.trackUploadSource((AbstractTexture) (Object) this, pixels);
    }
}

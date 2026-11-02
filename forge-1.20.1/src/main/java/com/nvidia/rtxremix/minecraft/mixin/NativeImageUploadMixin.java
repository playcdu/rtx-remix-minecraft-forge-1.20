/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.DynamicTextureCache;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies a {@link NativeImage} upload into whichever CPU mirror follows it: a glyph page
 * mirror, or the {@link DynamicTextureCache} snapshot of a tracked {@code DynamicTexture}.
 *
 * <p>Bitmap-font glyphs upload a window of the font's sheet, TrueType and built-in
 * glyphs a whole small image; both end in {@code _upload}, whose arguments are
 * exactly the GL sub-image call: destination slot, source window and size. Only an upload
 * inside a glyph upload scope is mirrored as a glyph.
 *
 * <p>A dynamic texture's image is tagged with its texture id when it is registered or
 * uploaded, so an upload from it, including the sub-rectangle uploads ImmediatelyFast's
 * map atlas makes, reaches the snapshot. Every other texture upload in the game costs
 * one static read and one field read.
 */
@Mixin(NativeImage.class)
public abstract class NativeImageUploadMixin implements DynamicTextureCache.UploadSource {

    @Shadow @Final private NativeImage.Format format;
    @Shadow private long pixels;

    @Shadow public abstract int getWidth();

    @Unique private ResourceLocation rtxremix$uploadTarget;

    @Override
    public ResourceLocation rtxremix$uploadTarget() {
        return rtxremix$uploadTarget;
    }

    @Override
    public void rtxremix$setUploadTarget(ResourceLocation id) {
        rtxremix$uploadTarget = id;
    }

    @Inject(method = "_upload", at = @At("HEAD"))
    private void rtxremix$mirrorUpload(int level, int offsetX, int offsetY,
                                       int unpackSkipPixels, int unpackSkipRows,
                                       int width, int height, boolean blur, boolean clamp,
                                       boolean mipmap, boolean close, CallbackInfo ci) {
        if (level != 0 || width <= 0 || height <= 0) {
            return;
        }
        ResourceLocation dynamicTexture = rtxremix$uploadTarget;
        if (dynamicTexture != null) {
            DynamicTextureCache.captureUploadedRegion((NativeImage) (Object) this, dynamicTexture,
                    offsetX, offsetY, unpackSkipPixels, unpackSkipRows, width, height);
        }
        GlyphPageMirror page = GlyphPageMirror.uploadTarget();
        if (page == null) {
            return;
        }
        int channels = format.components();
        int rowPitch = getWidth() * channels;
        long first = pixels + ((long) unpackSkipRows * getWidth() + unpackSkipPixels) * channels;
        // The window's rows stride the whole image; the buffer spans only the bytes the
        // window touches, from its first pixel to the end of its last row.
        int span = (height - 1) * rowPitch + width * channels;
        page.blit(offsetX, offsetY, width, height, channels,
                MemoryUtil.memByteBuffer(first, span), rowPitch);
    }
}

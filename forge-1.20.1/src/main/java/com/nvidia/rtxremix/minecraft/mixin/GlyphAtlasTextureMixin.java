/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import net.minecraft.client.gui.font.FontTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives every glyph atlas page a {@link GlyphPageMirror} and brackets each glyph upload
 * so the upload mixins know which page the pixels are for.
 *
 * <p>{@code add} is the one place a page is written: it binds the texture and calls the
 * glyph's {@code upload} with the slot it found. The glyph implementations reach GL
 * through two different primitives, {@code NativeImage._upload} for bitmap, TrueType and
 * built-in glyphs and {@code GlStateManager._upload} for Unihex, which is why the scope
 * is opened here and the pixels are read where they are uploaded.
 *
 * <p>The page size is read from the constructor's GL allocation rather than assumed:
 * ImmediatelyFast's font atlas resizing turns vanilla's 256 into 2048 throughout
 * {@code FontTexture}, and the mirror must match the slots and UVs the page hands out.
 */
@Mixin(FontTexture.class)
public abstract class GlyphAtlasTextureMixin implements GlyphPageMirror.Owner {

    @Shadow @Final private boolean colored;

    @Unique private GlyphPageMirror rtxremix$mirror;
    @Unique private int rtxremix$pageWidth = GlyphPageMirror.PAGE_SIZE;
    @Unique private int rtxremix$pageHeight = GlyphPageMirror.PAGE_SIZE;

    @WrapOperation(
        method = "<init>",
        at = @At(value = "INVOKE",
                target = "Lcom/mojang/blaze3d/platform/TextureUtil;prepareImage(Lcom/mojang/blaze3d/platform/NativeImage$InternalGlFormat;III)V")
    )
    private void rtxremix$recordPageSize(NativeImage.InternalGlFormat format, int textureId,
                                         int width, int height, Operation<Void> original) {
        rtxremix$pageWidth = width;
        rtxremix$pageHeight = height;
        original.call(format, textureId, width, height);
    }

    @Override
    public GlyphPageMirror rtxremix$glyphPageMirror() {
        // Created on first use: colored and the page size are assigned by the
        // constructor, so a field initialiser could not read them.
        if (rtxremix$mirror == null) {
            rtxremix$mirror = new GlyphPageMirror(!colored, rtxremix$pageWidth, rtxremix$pageHeight);
        }
        return rtxremix$mirror;
    }

    @WrapOperation(
        method = "add",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/font/SheetGlyphInfo;upload(II)V")
    )
    private void rtxremix$mirrorGlyphUpload(SheetGlyphInfo glyph, int x, int y, Operation<Void> original) {
        GlyphPageMirror.beginUpload(rtxremix$glyphPageMirror());
        try {
            original.call(glyph, x, y);
        } finally {
            GlyphPageMirror.endUpload();
        }
    }
}

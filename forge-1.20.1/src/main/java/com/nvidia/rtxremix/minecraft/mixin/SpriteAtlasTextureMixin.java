/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter;
import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import me.jellysquid.mods.sodium.client.render.texture.SpriteContentsExtended;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Brackets an atlas's animation tick so {@code SpriteContentsUploadMixin} knows which
 * atlas its patches belong to, and reports a finished stitch to the entity submitter.
 *
 * <p>Embeddium's "Animate Only Visible Textures" option skips the tick of every animated
 * sprite nothing marked active since the previous tick, and marks only what the camera
 * draws. The path tracer also sees what the camera does not, through reflections and
 * indirect light, so while Remix renders the world every animated block-atlas sprite is
 * marked active before the tick, which is what vanilla does unconditionally. The list is
 * gathered once per stitch so the per-tick cost is one flag write per animated sprite.
 */
@Mixin(TextureAtlas.class)
public abstract class SpriteAtlasTextureMixin {

    @Shadow @Final private ResourceLocation location;
    @Shadow private Map<ResourceLocation, TextureAtlasSprite> texturesByName;

    @Unique private TextureAtlasSprite[] rtxremix$animatedSprites = new TextureAtlasSprite[0];

    @Inject(method = "cycleAnimationFrames", at = @At("HEAD"))
    private void rtxremix$onAnimatedSpriteTickStart(CallbackInfo ci) {
        if (rtxremix$animatedSprites.length > 0 && RtxRemixMod.isRemixWorldRendering()) {
            for (TextureAtlasSprite sprite : rtxremix$animatedSprites) {
                if (sprite.contents() instanceof SpriteContentsExtended tracked) {
                    tracked.sodium$setActive(true);
                }
            }
        }
        AtlasAnimationForwarder.beginAtlasUpload(location);
    }

    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"))
    private void rtxremix$onAnimatedSpriteTickEnd(CallbackInfo ci) {
        AtlasAnimationForwarder.endAtlasUpload();
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void rtxremix$onAtlasUploaded(SpriteLoader.Preparations stitchResult, CallbackInfo ci) {
        rtxremix$animatedSprites = location.equals(BlockTextureManager.BLOCK_ATLAS_ID)
                ? rtxremix$collectAnimatedSprites(texturesByName)
                : new TextureAtlasSprite[0];
        RemixEntitySubmitter.onMinecraftAtlasUploaded(location);
    }

    @Unique
    private static TextureAtlasSprite[] rtxremix$collectAnimatedSprites(
            Map<ResourceLocation, TextureAtlasSprite> sprites) {
        List<TextureAtlasSprite> animated = new ArrayList<>();
        for (TextureAtlasSprite sprite : sprites.values()) {
            if (((SpriteContentsAccessor) sprite.contents()).getAnimation() != null) {
                animated.add(sprite);
            }
        }
        return animated.toArray(new TextureAtlasSprite[0]);
    }
}

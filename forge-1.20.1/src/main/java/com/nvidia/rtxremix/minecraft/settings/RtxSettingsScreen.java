/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.minecraft.input.RemixInputGate;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget; // PORT: no 1.20.1 equivalent
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Landing page for the categorized RTX Remix settings. */
public final class RtxSettingsScreen extends Screen {

    private final Screen parent;
    private final RtxSettings settings;
    private final Runnable saveSettings;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 33);

    public RtxSettingsScreen(Screen parent, RtxSettings settings, Runnable saveSettings) {
        super(Component.translatable("rtxremix.settings.title"));
        this.parent = parent;
        this.settings = settings;
        this.saveSettings = saveSettings;
    }

    @Override
    protected void init() {
        DirectionalLayoutWidget header =
                this.layout.addHeader(DirectionalLayoutWidget.vertical());
        header.add(new StringWidget(this.title, this.font), LayoutSettings::alignHorizontallyCenter);

        DirectionalLayoutWidget categories = DirectionalLayoutWidget.vertical().spacing(8);
        for (SettingCategory category : SettingCategory.values()) {
            if (settings.category(category).isEmpty()) {
                continue;
            }
            categories.add(Button.builder(
                            Component.translatable(category.labelKey()),
                            button -> this.client.setScreen(new RtxSettingsCategoryScreen(
                                    this, settings, saveSettings, category)))
                    .width(204)
                    .build());
        }
        // Set apart from the categories because it leaves Minecraft's screens
        // for Remix's own menu rather than opening another page.
        categories.add(openRemixMenuButton(), positioner -> positioner.paddingTop(8));
        this.layout.addToContents(categories);

        DirectionalLayoutWidget footer = DirectionalLayoutWidget.horizontal().spacing(8);
        footer.add(Button.builder(
                        Component.translatable("rtxremix.settings.resetAll"), button -> confirmReset())
                .tooltip(Tooltip.create(Component.translatable("rtxremix.settings.resetAll.tip")))
                .width(98)
                .build());
        footer.add(Button.builder(CommonComponents.DONE, button -> close()).width(98).build());
        this.layout.addToFooter(footer);

        this.layout.forEachChild(this::addRenderableWidget);
        this.repositionElements();
    }

    private Button openRemixMenuButton() {
        return Button.builder(
                        Component.translatable("rtxremix.settings.openRemixMenu"),
                        // Queued so the click that pressed this button finishes
                        // dispatching before opening releases that same press.
                        button -> this.client.send(() -> RtxRemixMod.openRemixMenu(
                                RemixInputGate.Menu.GRAPHICS_SETTINGS)))
                .tooltip(Tooltip.create(Component.translatable("rtxremix.settings.openRemixMenu.tip")))
                .width(204)
                .build();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
    }

    /**
     * Resets every setting, then reopens this page as a new screen instance.
     *
     * <p>A refreshed page must be a fresh screen, never {@code clearAndInit()}
     * on this one. The {@code layout} field is built once, in its initializer,
     * and is never cleared, so re-running {@code init()} appends a second
     * header, body, and footer to the same layout. The same rule holds for the
     * category and block-light pages, where the stacking is visible: their
     * option lists draw a translucent background, so every refresh would add
     * another dark layer over a duplicated set of widgets.
     */
    private void confirmReset() {
        this.client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                boolean rebuildBlockLights =
                        settings.hasOverrides(SettingGroup.BLOCK_LIGHTS);
                settings.resetAll();
                BlockTextureManager textures = BlockTextureManager.get();
                boolean rebuildMaterials = textures != null
                        && textures.commitTerrainEmissionMultiplier();
                if (saveSettings != null) {
                    saveSettings.run();
                }
                if (rebuildMaterials) {
                    RtxRemixMod.rebuildTerrainForMaterialSettings();
                } else if (rebuildBlockLights) {
                    RtxRemixMod.rebuildLoadedTerrain();
                }
            }
            this.client.setScreen(new RtxSettingsScreen(parent, settings, saveSettings));
        }, Component.translatable("rtxremix.settings.resetAll.confirm.title"),
                Component.translatable("rtxremix.settings.resetAll.confirm.message")));
    }

    @Override
    public void onClose() {
        if (saveSettings != null) {
            saveSettings.run();
        }
        this.client.setScreen(this.parent);
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.minecraft.input.RemixInputGate;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Landing page for the categorized RTX Remix settings. */
public final class RtxSettingsScreen extends Screen {

    private static final int HEADER_HEIGHT = 33;
    private static final int FOOTER_HEIGHT = 33;
    private static final int SPACING = 8;

    private final Screen parent;
    private final RtxSettings settings;
    private final Runnable saveSettings;
    private HeaderAndFooterLayout layout;

    public RtxSettingsScreen(Screen parent, RtxSettings settings, Runnable saveSettings) {
        super(Component.translatable("rtxremix.settings.title"));
        this.parent = parent;
        this.settings = settings;
        this.saveSettings = saveSettings;
    }

    @Override
    protected void init() {
        this.layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, FOOTER_HEIGHT);
        this.layout.addToHeader(new StringWidget(this.title, this.font));

        GridLayout categories = new GridLayout().rowSpacing(SPACING);
        GridLayout.RowHelper categoryRows = categories.createRowHelper(1);
        for (SettingCategory category : SettingCategory.values()) {
            if (settings.category(category).isEmpty()) {
                continue;
            }
            categoryRows.addChild(Button.builder(
                            Component.translatable(category.labelKey()),
                            button -> this.minecraft.setScreen(new RtxSettingsCategoryScreen(
                                    this, settings, saveSettings, category)))
                    .width(204)
                    .build());
        }
        // Set apart from the categories because it leaves Minecraft's screens
        // for Remix's own menu rather than opening another page.
        categoryRows.addChild(
                openRemixMenuButton(), categoryRows.newCellSettings().paddingTop(SPACING));
        this.layout.addToContents(categories);

        GridLayout footer = new GridLayout().columnSpacing(SPACING);
        GridLayout.RowHelper footerRow = footer.createRowHelper(2);
        footerRow.addChild(Button.builder(
                        Component.translatable("rtxremix.settings.resetAll"), button -> confirmReset())
                .tooltip(Tooltip.create(Component.translatable("rtxremix.settings.resetAll.tip")))
                .width(98)
                .build());
        footerRow.addChild(
                Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(98).build());
        this.layout.addToFooter(footer);

        this.layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    private Button openRemixMenuButton() {
        return Button.builder(
                        Component.translatable("rtxremix.settings.openRemixMenu"),
                        // Queued so the click that pressed this button finishes
                        // dispatching before opening releases that same press.
                        button -> this.minecraft.tell(() -> RtxRemixMod.openRemixMenu(
                                RemixInputGate.Menu.GRAPHICS_SETTINGS)))
                .tooltip(Tooltip.create(Component.translatable("rtxremix.settings.openRemixMenu.tip")))
                .width(204)
                .build();
    }

    /** A resize only repositions the page, as 1.21.1's tab navigation refresh did. */
    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
    }

    /**
     * Resets every setting, then reopens this page as a new screen instance.
     *
     * <p>A refreshed page is a fresh screen, never a rebuild of this one, so
     * every widget, here and on the category and block-light pages, is built
     * again from the reset values rather than carrying a stale value or a
     * pending slider position across the reset.
     */
    private void confirmReset() {
        this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
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
            this.minecraft.setScreen(new RtxSettingsScreen(parent, settings, saveSettings));
        }, Component.translatable("rtxremix.settings.resetAll.confirm.title"),
                Component.translatable("rtxremix.settings.resetAll.confirm.message")));
    }

    @Override
    public void onClose() {
        if (saveSettings != null) {
            saveSettings.run();
        }
        this.minecraft.setScreen(this.parent);
    }
}

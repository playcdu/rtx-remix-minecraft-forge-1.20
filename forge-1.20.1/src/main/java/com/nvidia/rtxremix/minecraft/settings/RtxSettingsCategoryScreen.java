/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.OptionsSubScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget; // PORT: no 1.20.1 equivalent
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/** Native options list for one RTX settings category. */
final class RtxSettingsCategoryScreen extends OptionsSubScreen {

    private final RtxSettings settings;
    private final Runnable saveSettings;
    private final SettingCategory category;

    RtxSettingsCategoryScreen(
            Screen parent,
            RtxSettings settings,
            Runnable saveSettings,
            SettingCategory category) {
        super(parent, Minecraft.getInstance().options, Component.translatable(category.labelKey()));
        this.settings = settings;
        this.saveSettings = saveSettings;
        this.category = category;
    }

    @Override
    protected void addOptions() {
        for (SettingGroup group : SettingGroup.values()) {
            if (group.category() != category) {
                continue;
            }
            List<RtxSetting<?>> groupSettings = settings.group(group);
            if (groupSettings.isEmpty()) {
                continue;
            }
            if (opensSubscreen(group)) {
                this.body.addSmall(
                        new StringWidget(
                                150,
                                20,
                                Component.translatable(group.labelKey()).withStyle(ChatFormatting.BOLD),
                                this.font),
                        Button.builder(
                                        Component.translatable("rtxremix.settings.configure"),
                                        button -> this.client.setScreen(
                                                new RtxBlockLightsScreen(
                                                        this, settings, saveSettings)))
                                .width(150)
                                .build());
                continue;
            }

            StringWidget header = new StringWidget(
                    310,
                    20,
                    Component.translatable(group.labelKey()).withStyle(ChatFormatting.BOLD),
                    this.font);
            this.body.addSmall(header, null);

            List<OptionInstance<?>> options = new ArrayList<>();
            for (RtxSetting<?> setting : groupSettings) {
                options.add(RtxOptionFactory.toOption(setting));
            }
            if (fullWidthRows(group)) {
                options.forEach(this.body::addBig);
            } else {
                this.body.addAll(options.toArray(new OptionInstance<?>[0]));
            }
        }
    }

    @Override
    protected void createFooter() {
        DirectionalLayoutWidget footer = DirectionalLayoutWidget.horizontal().spacing(8);
        footer.add(Button.builder(
                        Component.translatable("rtxremix.settings.reset"), button -> confirmReset())
                .tooltip(Tooltip.create(Component.translatable("rtxremix.settings.reset.tip")))
                .width(98)
                .build());
        footer.add(Button.builder(CommonComponents.DONE, button -> close()).width(98).build());
        this.layout.addToFooter(footer);
    }

    private void confirmReset() {
        this.client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                resetCurrentScreen();
                if (saveSettings != null) {
                    saveSettings.run();
                }
                if (commitTerrainEmission()) {
                    RtxRemixMod.rebuildTerrainForMaterialSettings();
                }
                this.client.setScreen(
                        new RtxSettingsCategoryScreen(
                                this.parent, settings, saveSettings, category));
            } else {
                this.client.setScreen(this);
            }
        }, Component.translatable("rtxremix.settings.reset.confirm.title",
                        Component.translatable(category.labelKey())),
                Component.translatable("rtxremix.settings.reset.confirm.message",
                        Component.translatable(category.labelKey()))));
    }

    private void resetCurrentScreen() {
        for (SettingGroup group : resettableGroups(category)) {
            settings.resetGroup(group);
        }
    }

    static List<SettingGroup> resettableGroups(SettingCategory category) {
        List<SettingGroup> groups = new ArrayList<>();
        for (SettingGroup group : SettingGroup.values()) {
            if (group.category() == category && !opensSubscreen(group)) {
                groups.add(group);
            }
        }
        return List.copyOf(groups);
    }

    private static boolean opensSubscreen(SettingGroup group) {
        return group == SettingGroup.BLOCK_LIGHTS;
    }

    private static boolean fullWidthRows(SettingGroup group) {
        return group == SettingGroup.FRAMERATE;
    }

    private boolean commitTerrainEmission() {
        BlockTextureManager textures = BlockTextureManager.get();
        return category == SettingCategory.MATERIALS
                && textures != null
                && textures.commitTerrainEmissionMultiplier();
    }

    @Override
    public void close() {
        if (this.body != null) {
            this.body.applyAllPendingValues();
        }
        if (saveSettings != null) {
            saveSettings.run();
        }
        if (commitTerrainEmission()) {
            RtxRemixMod.rebuildTerrainForMaterialSettings();
        }
        this.client.setScreen(this.parent);
    }
}

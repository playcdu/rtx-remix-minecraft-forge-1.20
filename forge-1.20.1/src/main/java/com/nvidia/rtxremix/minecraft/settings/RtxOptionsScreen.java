/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.OptionsSubScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The shape of 1.21.1's {@code GameOptionsScreen}, which these pages were
 * written against, built on 1.20.1's {@link OptionsSubScreen}: a centred
 * title in a 33-pixel header, an {@link RtxOptionsList} body between header
 * and footer, and a footer subclasses fill through {@link #initFooter()}.
 *
 * <p>The layout and list are rebuilt by each {@link #init()}. A resize only
 * repositions them ({@link #repositionElements()}), so a slider's pending value
 * survives it, as it did on 1.21.1.
 */
abstract class RtxOptionsScreen extends OptionsSubScreen {

    static final int HEADER_HEIGHT = 33;
    static final int FOOTER_HEIGHT = 33;

    protected HeaderAndFooterLayout layout;
    protected RtxOptionsList body;

    RtxOptionsScreen(Screen parent, Component title) {
        super(parent, Minecraft.getInstance().options, title);
    }

    @Override
    protected void init() {
        this.layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, FOOTER_HEIGHT);
        this.layout.addToHeader(new StringWidget(this.title, this.font));
        this.body = new RtxOptionsList(
                this.minecraft, this.width, this.height, HEADER_HEIGHT, this.height - FOOTER_HEIGHT);
        addOptions();
        initFooter();
        addWidget(this.body);
        this.layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    /** Fills {@link #body}. */
    protected abstract void addOptions();

    /** Fills the footer; the default is a single Done button. */
    protected void initFooter() {
        this.layout.addToFooter(
                Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(200).build());
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        this.body.updateSize(this.width, this.height, HEADER_HEIGHT, this.height - FOOTER_HEIGHT);
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        this.body.render(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
    }
}

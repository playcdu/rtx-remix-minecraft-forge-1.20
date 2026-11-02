/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

/**
 * The options list the RTX settings pages use, standing in for 1.21.1's
 * {@code OptionListWidget}.
 *
 * <p>1.20.1's {@code OptionsList} holds only option rows: its entry
 * constructor is private, so it cannot hold the header labels and
 * label-plus-button rows these pages need, and it has no
 * {@code applyAllPendingValues()} for deferred sliders. This list keeps the
 * same geometry (25-pixel rows, a 310-pixel full row, two 150-pixel halves
 * 10 pixels apart, centred) and adds both.
 */
final class RtxOptionsList extends ContainerObjectSelectionList<RtxOptionsList.Row> {

    static final int ITEM_HEIGHT = 25;
    private static final int FULL_WIDTH = 310;
    private static final int HALF_WIDTH = 150;
    private static final int COLUMN_OFFSET = 160;

    private final Minecraft minecraft;

    RtxOptionsList(Minecraft minecraft, int width, int height, int top, int bottom) {
        super(minecraft, width, height, top, bottom, ITEM_HEIGHT);
        this.minecraft = minecraft;
        this.centerListVertically = false;
    }

    /** Adds one option across the full row width. */
    void addSingleOptionEntry(OptionInstance<?> option) {
        addEntry(new Row(
                option.createButton(minecraft.options, 0, 0, FULL_WIDTH), null, true));
    }

    /** Adds options two to a row, the last alone when the count is odd. */
    void addAll(OptionInstance<?>... options) {
        for (int i = 0; i < options.length; i += 2) {
            AbstractWidget first = options[i].createButton(minecraft.options, 0, 0, HALF_WIDTH);
            AbstractWidget second = i + 1 < options.length
                    ? options[i + 1].createButton(minecraft.options, 0, 0, HALF_WIDTH)
                    : null;
            addEntry(new Row(first, second, false));
        }
    }

    /**
     * Adds a row of one or two caller-built widgets, placed in the left and
     * right columns. A lone widget wider than a column starts at the left
     * column and keeps its own width, as a header label does.
     */
    void addWidgetEntry(AbstractWidget first, @Nullable AbstractWidget second) {
        addEntry(new Row(first, second, false));
    }

    /** Commits every deferred slider's pending value, as screens do on close. */
    void applyAllPendingValues() {
        for (Row row : children()) {
            for (AbstractWidget widget : row.widgets) {
                if (widget instanceof RtxOptionFactory.OptionSlider<?> slider) {
                    slider.applyPendingValue();
                }
            }
        }
    }

    @Override
    public int getRowWidth() {
        return 400;
    }

    @Override
    protected int getScrollbarPosition() {
        return super.getScrollbarPosition() + 32;
    }

    /** One list row of up to two widgets. */
    static final class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final AbstractWidget first;
        @Nullable
        private final AbstractWidget second;
        private final boolean fullWidth;
        private final List<AbstractWidget> widgets;

        private Row(AbstractWidget first, @Nullable AbstractWidget second, boolean fullWidth) {
            this.first = first;
            this.second = second;
            this.fullWidth = fullWidth;
            List<AbstractWidget> list = new ArrayList<>(2);
            list.add(first);
            if (second != null) {
                list.add(second);
            }
            this.widgets = List.copyOf(list);
        }

        @Override
        public void render(
                GuiGraphics context,
                int index,
                int top,
                int left,
                int width,
                int height,
                int mouseX,
                int mouseY,
                boolean hovered,
                float delta) {
            // Vanilla's OptionsList places its columns from the list's own centre,
            // not the row's, which getRowLeft() offsets by two pixels.
            int center = left - 2 + width / 2;
            int columnX = center - FULL_WIDTH / 2;
            first.setX(fullWidth ? center - first.getWidth() / 2 : columnX);
            first.setY(top);
            first.render(context, mouseX, mouseY, delta);
            if (second != null) {
                second.setX(columnX + COLUMN_OFFSET);
                second.setY(top);
                second.render(context, mouseX, mouseY, delta);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }
    }
}

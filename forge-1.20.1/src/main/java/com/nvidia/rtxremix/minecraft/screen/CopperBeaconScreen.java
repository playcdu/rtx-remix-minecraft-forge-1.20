/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.screen;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconAnimationMode;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconAxisState;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenHandler;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconUpdatePayload;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import com.mojang.blaze3d.systems.RenderSystem;

/** Minecraft-style per-block controls for the Copper Beacon. */
public final class CopperBeaconScreen extends AbstractContainerScreen<CopperBeaconScreenHandler> {

    private static final int BACKGROUND_HEIGHT = 230;
    private static final int OUTER_MARGIN = 8;
    private static final int PANEL_GAP = 8;
    private static final int PANEL_WIDTH =
            (CopperBeaconScreenHandler.WIDTH - OUTER_MARGIN * 2 - PANEL_GAP) / 2;
    private static final int CONTROL_HEIGHT = 16;
    private static final int RANGE_SLIDER_GAP = 6;
    private static final int RANGE_SLIDER_WIDTH =
            (PANEL_WIDTH - RANGE_SLIDER_GAP) / 2;
    /**
     * 1.20.1 has no GUI sprite atlas (1.21's {@code container/slot} sprite), so
     * the slot frame is cut from the generic container background, where the
     * first slot's 18x18 frame starts at (7, 17).
     */
    private static final ResourceLocation SLOT_TEXTURE =
            new ResourceLocation("textures/gui/container/generic_54.png");
    private static final int SLOT_TEXTURE_U = 7;
    private static final int SLOT_TEXTURE_V = 17;

    private final Map<CopperBeaconUpdatePayload.Field, Float> pending =
            new EnumMap<>(CopperBeaconUpdatePayload.Field.class);

    public CopperBeaconScreen(
            CopperBeaconScreenHandler handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
        imageWidth = CopperBeaconScreenHandler.WIDTH;
        imageHeight = BACKGROUND_HEIGHT;
        inventoryLabelX = CopperBeaconScreenHandler.PLAYER_INVENTORY_X;
        inventoryLabelY = CopperBeaconScreenHandler.PLAYER_INVENTORY_Y - 12;
        titleLabelX = 8;
        titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        // A side-mounted panel leaves the crosshair area unobstructed so the
        // articulated beacon remains visible while its controls are adjusted.
        if (width >= imageWidth + 16) {
            leftPos = 8;
        }
        buildControls();
    }

    private void buildControls() {
        CopperBeaconBlockEntity beacon = menu.getBeacon();
        if (beacon == null) {
            return;
        }

        addSlider(
                leftPos + 56,
                topPos + 17,
                94,
                "container.rtxremix.copper_beacon.saturation",
                beacon.getSaturation(),
                value -> value / CopperBeaconBlockEntity.MAX_SATURATION,
                value -> value * CopperBeaconBlockEntity.MAX_SATURATION,
                value -> Component.literal(String.format(Locale.ROOT, "%.0f%%", value * 100.0)),
                CopperBeaconUpdatePayload.Field.SATURATION);

        BeaconCheckbox redstone = new BeaconCheckbox(
                leftPos + 158,
                topPos + 17,
                Component.translatable("container.rtxremix.copper_beacon.redstone"),
                beacon.isRedstoneControlled(),
                checked -> queue(
                        CopperBeaconUpdatePayload.Field.REDSTONE_CONTROLLED,
                        checked ? 1.0f : 0.0f));
        redstone.setTooltip(Tooltip.create(Component.translatable(
                "container.rtxremix.copper_beacon.redstone.tip")));
        addRenderableWidget(redstone);

        addSlider(
                leftPos + OUTER_MARGIN,
                topPos + 36,
                118,
                "container.rtxremix.copper_beacon.intensity",
                beacon.getIntensityMultiplier(),
                CopperBeaconScreen::intensityToSlider,
                CopperBeaconScreen::sliderToIntensity,
                value -> value == 0.0
                        ? Component.translatable("options.off")
                        : Component.literal(String.format(Locale.ROOT, "%.2f\u00d7", value)),
                CopperBeaconUpdatePayload.Field.INTENSITY);
        addSlider(
                leftPos + 134,
                topPos + 36,
                118,
                "container.rtxremix.copper_beacon.cone",
                beacon.getConeAngle(),
                value -> value / CopperBeaconBlockEntity.MAX_CONE_ANGLE,
                value -> value * CopperBeaconBlockEntity.MAX_CONE_ANGLE,
                value -> Component.literal(String.format(Locale.ROOT, "%.1f\u00b0", value)),
                CopperBeaconUpdatePayload.Field.CONE_ANGLE);

        addAxisControls(
                leftPos + OUTER_MARGIN,
                topPos + 54,
                beacon.getYaw(),
                true);
        addAxisControls(
                leftPos + OUTER_MARGIN + PANEL_WIDTH + PANEL_GAP,
                topPos + 54,
                beacon.getPitch(),
                false);
    }

    private void addAxisControls(
            int panelX,
            int panelY,
            CopperBeaconAxisState state,
            boolean yaw) {
        addSlider(
                panelX,
                panelY + 11,
                PANEL_WIDTH,
                "container.rtxremix.copper_beacon.position",
                state.position(),
                value -> (value - CopperBeaconAxisState.MIN_ANGLE)
                        / (CopperBeaconAxisState.MAX_ANGLE - CopperBeaconAxisState.MIN_ANGLE),
                value -> CopperBeaconAxisState.MIN_ANGLE
                        + value * (CopperBeaconAxisState.MAX_ANGLE
                                - CopperBeaconAxisState.MIN_ANGLE),
                value -> Component.literal(String.format(Locale.ROOT, "%.1f\u00b0", value)),
                yaw
                        ? CopperBeaconUpdatePayload.Field.YAW_POSITION
                        : CopperBeaconUpdatePayload.Field.PITCH_POSITION);

        addRenderableWidget(CycleButton
                .<CopperBeaconAnimationMode>builder(CopperBeaconScreen::modeText)
                .withValues(CopperBeaconAnimationMode.values())
                .withInitialValue(state.mode())
                .create(
                        panelX,
                        panelY + 29,
                        PANEL_WIDTH,
                        CONTROL_HEIGHT,
                        Component.translatable(
                                "container.rtxremix.copper_beacon.animation"),
                        (button, mode) -> {
                            queue(
                                    yaw
                                            ? CopperBeaconUpdatePayload.Field.YAW_MODE
                                            : CopperBeaconUpdatePayload.Field.PITCH_MODE,
                                    mode.ordinal());
                            rebuildControls();
                        }));

        switch (state.mode()) {
            case STATIC -> {
            }
            case ROTATE -> addSlider(
                    panelX,
                    panelY + 47,
                    PANEL_WIDTH,
                    "container.rtxremix.copper_beacon.rotationSpeed",
                    state.rotationSpeed(),
                    CopperBeaconScreen::signedSpeedToSlider,
                    CopperBeaconScreen::sliderToSignedSpeed,
                    value -> Component.literal(String.format(Locale.ROOT, "%.1f\u00b0/s", value)),
                    yaw
                            ? CopperBeaconUpdatePayload.Field.YAW_ROTATION_SPEED
                            : CopperBeaconUpdatePayload.Field.PITCH_ROTATION_SPEED);
            case OSCILLATE, PATROL -> {
                addSlider(
                        panelX,
                        panelY + 47,
                        RANGE_SLIDER_WIDTH,
                        "container.rtxremix.copper_beacon.minimum",
                        state.minimumOffset(),
                        CopperBeaconScreen::angleToSlider,
                        CopperBeaconScreen::sliderToAngle,
                        value -> Component.literal(String.format(Locale.ROOT, "%.0f\u00b0", value)),
                        yaw
                                ? CopperBeaconUpdatePayload.Field.YAW_MINIMUM_OFFSET
                                : CopperBeaconUpdatePayload.Field.PITCH_MINIMUM_OFFSET);
                addSlider(
                        panelX + RANGE_SLIDER_WIDTH + RANGE_SLIDER_GAP,
                        panelY + 47,
                        RANGE_SLIDER_WIDTH,
                        "container.rtxremix.copper_beacon.maximum",
                        state.maximumOffset(),
                        CopperBeaconScreen::angleToSlider,
                        CopperBeaconScreen::sliderToAngle,
                        value -> Component.literal(String.format(Locale.ROOT, "%.0f\u00b0", value)),
                        yaw
                                ? CopperBeaconUpdatePayload.Field.YAW_MAXIMUM_OFFSET
                                : CopperBeaconUpdatePayload.Field.PITCH_MAXIMUM_OFFSET);
                boolean oscillate = state.mode() == CopperBeaconAnimationMode.OSCILLATE;
                addSlider(
                        panelX,
                        panelY + 65,
                        PANEL_WIDTH,
                        oscillate
                                ? "container.rtxremix.copper_beacon.cycle"
                                : "container.rtxremix.copper_beacon.patrol",
                        oscillate ? state.cycleSeconds() : state.patrolSeconds(),
                        CopperBeaconScreen::durationToSlider,
                        CopperBeaconScreen::sliderToDuration,
                        value -> Component.literal(String.format(Locale.ROOT, "%.2fs", value)),
                        oscillate
                                ? (yaw
                                        ? CopperBeaconUpdatePayload.Field.YAW_CYCLE_SECONDS
                                        : CopperBeaconUpdatePayload.Field.PITCH_CYCLE_SECONDS)
                                : (yaw
                                        ? CopperBeaconUpdatePayload.Field.YAW_PATROL_SECONDS
                                        : CopperBeaconUpdatePayload.Field.PITCH_PATROL_SECONDS));
            }
        }
    }

    private void rebuildControls() {
        clearWidgets();
        buildControls();
    }

    private void addSlider(
            int x,
            int y,
            int width,
            String labelKey,
            double current,
            DoubleUnaryOperator toSlider,
            DoubleUnaryOperator fromSlider,
            Function<Double, Component> valueText,
            CopperBeaconUpdatePayload.Field field) {
        addRenderableWidget(new BeaconSlider(
                x,
                y,
                width,
                Component.translatable(labelKey),
                Component.translatable(labelKey + ".tip"),
                Mth.clamp(toSlider.applyAsDouble(current), 0.0, 1.0),
                fromSlider,
                valueText,
                value -> queue(field, value.floatValue()),
                this::flushPending));
    }

    private void queue(CopperBeaconUpdatePayload.Field field, float value) {
        menu.applyUpdate(field, value);
        pending.put(field, value);
    }

    private void flushPending() {
        if (pending.isEmpty()) {
            return;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null
                || !CopperBeaconUpdatePayload.canSend(connection.getConnection())) {
            pending.clear();
            return;
        }
        pending.forEach((field, value) -> CopperBeaconUpdatePayload.send(
                new CopperBeaconUpdatePayload(menu.containerId, field, value)));
        pending.clear();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        flushPending();
    }

    @Override
    public void removed() {
        flushPending();
        super.removed();
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        // 1.21.1's Screen.render draws the in-game background itself; 1.20.1
        // leaves it to each screen.
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        renderTooltip(context, mouseX, mouseY);
    }

    @Override
    protected void renderBg(
            GuiGraphics context, float delta, int mouseX, int mouseY) {
        context.renderOutline(leftPos, topPos, imageWidth, imageHeight, 0xFF8B8B8B);
        for (var slot : menu.slots) {
            context.blit(
                    SLOT_TEXTURE,
                    leftPos + slot.x - 1,
                    topPos + slot.y - 1,
                    SLOT_TEXTURE_U,
                    SLOT_TEXTURE_V,
                    18,
                    18);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics context, int mouseX, int mouseY) {
        context.drawString(
                font,
                title,
                titleLabelX,
                titleLabelY,
                0xFFE0E0E0,
                false);
        context.drawString(
                font,
                playerInventoryTitle,
                inventoryLabelX,
                inventoryLabelY,
                0xFFE0E0E0,
                false);
        context.drawString(
                font,
                Component.translatable("container.rtxremix.copper_beacon.yaw"),
                OUTER_MARGIN,
                55,
                0xFFE0E0E0,
                false);
        context.drawString(
                font,
                Component.translatable("container.rtxremix.copper_beacon.pitch"),
                OUTER_MARGIN + PANEL_WIDTH + PANEL_GAP,
                55,
                0xFFE0E0E0,
                false);
        context.drawString(
                font,
                Component.translatable("container.rtxremix.copper_beacon.dye"),
                OUTER_MARGIN,
                22,
                0xFFE0E0E0,
                false);
    }

    private static Component modeText(CopperBeaconAnimationMode mode) {
        return Component.translatable(
                "container.rtxremix.copper_beacon.mode."
                        + mode.name().toLowerCase(Locale.ROOT));
    }

    static double intensityToSlider(double value) {
        if (value <= 0.0) {
            return 0.0;
        }
        return value <= 1.0
                ? (Math.log10(value) + 2.0) / 4.0
                : (Math.log10(value) + 3.0) / 6.0;
    }

    static double sliderToIntensity(double slider) {
        if (slider == 0.0) {
            return 0.0;
        }
        return slider <= 0.5
                ? Math.pow(10.0, slider * 4.0 - 2.0)
                : Math.pow(10.0, slider * 6.0 - 3.0);
    }

    static double angleToSlider(double value) {
        return (value - CopperBeaconAxisState.MIN_ANGLE)
                / (CopperBeaconAxisState.MAX_ANGLE - CopperBeaconAxisState.MIN_ANGLE);
    }

    static double sliderToAngle(double slider) {
        return CopperBeaconAxisState.MIN_ANGLE
                + slider * (CopperBeaconAxisState.MAX_ANGLE - CopperBeaconAxisState.MIN_ANGLE);
    }

    static double signedSpeedToSlider(double value) {
        if (value == 0.0) {
            return 0.5;
        }
        double magnitude = (Math.log10(Math.abs(value)) + 1.0)
                / (Math.log10(CopperBeaconAxisState.MAX_ROTATION_SPEED) + 1.0);
        return 0.5 + Math.copySign(0.5 * magnitude, value);
    }

    static double sliderToSignedSpeed(double slider) {
        double centered = slider * 2.0 - 1.0;
        if (Math.abs(centered) < 0.01) {
            return 0.0;
        }
        double exponent = Math.abs(centered)
                * (Math.log10(CopperBeaconAxisState.MAX_ROTATION_SPEED) + 1.0)
                - 1.0;
        return Math.copySign(Math.pow(10.0, exponent), centered);
    }

    static double durationToSlider(double value) {
        return Math.log(value / CopperBeaconAxisState.MIN_DURATION_SECONDS)
                / Math.log(CopperBeaconAxisState.MAX_DURATION_SECONDS
                        / CopperBeaconAxisState.MIN_DURATION_SECONDS);
    }

    static double sliderToDuration(double slider) {
        return CopperBeaconAxisState.MIN_DURATION_SECONDS * Math.pow(
                CopperBeaconAxisState.MAX_DURATION_SECONDS
                        / CopperBeaconAxisState.MIN_DURATION_SECONDS,
                slider);
    }

    @FunctionalInterface
    private interface ValueConsumer {
        void accept(Double value);
    }

    /**
     * 1.21.1's checkbox builder, rebuilt on 1.20.1's {@link Checkbox}: a box
     * sized to the font ({@code fontHeight + 8}, as 1.21.1 sizes it) rather
     * than 1.20.1's fixed 20 pixels, so it sits in the same 16-pixel control
     * row, and a callback after each toggle.
     */
    private static final class BeaconCheckbox extends Checkbox {
        private static final ResourceLocation TEXTURE =
                new ResourceLocation("textures/gui/checkbox.png");
        private static final int TEXTURE_BOX = 20;
        private static final int TEXTURE_SIZE = 64;
        private static final int LABEL_GAP = 4;
        private static final int TEXT_COLOR = 0xE0E0E0;

        private final Consumer<Boolean> callback;

        private BeaconCheckbox(
                int x, int y, Component label, boolean checked, Consumer<Boolean> callback) {
            super(x, y,
                    boxSize() + LABEL_GAP + Minecraft.getInstance().font.width(label),
                    boxSize(),
                    label,
                    checked);
            this.callback = callback;
        }

        private static int boxSize() {
            return Minecraft.getInstance().font.lineHeight + 8;
        }

        @Override
        public void onPress() {
            super.onPress();
            callback.accept(selected());
        }

        @Override
        public void renderWidget(GuiGraphics context, int mouseX, int mouseY, float delta) {
            Font font = Minecraft.getInstance().font;
            int box = height;
            RenderSystem.enableDepthTest();
            context.setColor(1.0f, 1.0f, 1.0f, alpha);
            RenderSystem.enableBlend();
            context.blit(
                    TEXTURE,
                    getX(),
                    getY(),
                    box,
                    box,
                    isFocused() ? TEXTURE_BOX : 0.0f,
                    selected() ? TEXTURE_BOX : 0.0f,
                    TEXTURE_BOX,
                    TEXTURE_BOX,
                    TEXTURE_SIZE,
                    TEXTURE_SIZE);
            context.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            context.drawString(
                    font,
                    getMessage(),
                    getX() + box + LABEL_GAP,
                    getY() + (box - font.lineHeight + 1) / 2,
                    TEXT_COLOR | Mth.ceil(alpha * 255.0f) << 24);
        }
    }

    private static final class BeaconSlider extends AbstractSliderButton {
        private final Component label;
        private final DoubleUnaryOperator fromSlider;
        private final Function<Double, Component> valueText;
        private final ValueConsumer consumer;
        private final Runnable release;

        private BeaconSlider(
                int x,
                int y,
                int width,
                Component label,
                Component tooltip,
                double value,
                DoubleUnaryOperator fromSlider,
                Function<Double, Component> valueText,
                ValueConsumer consumer,
                Runnable release) {
            super(x, y, width, CONTROL_HEIGHT, CommonComponents.EMPTY, value);
            this.label = label;
            this.fromSlider = fromSlider;
            this.valueText = valueText;
            this.consumer = consumer;
            this.release = release;
            setTooltip(Tooltip.create(tooltip));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double actual = fromSlider.applyAsDouble(value);
            setMessage(CommonComponents.optionNameValue(label, valueText.apply(actual)));
        }

        @Override
        protected void applyValue() {
            consumer.accept(fromSlider.applyAsDouble(value));
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            boolean released = super.mouseReleased(mouseX, mouseY, button);
            release.run();
            return released;
        }
    }
}

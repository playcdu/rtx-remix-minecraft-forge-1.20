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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking; // PORT: Fabric API
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

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
    private static final ResourceLocation SLOT_TEXTURE = new ResourceLocation("container/slot");

    private final Map<CopperBeaconUpdatePayload.Field, Float> pending =
            new EnumMap<>(CopperBeaconUpdatePayload.Field.class);

    public CopperBeaconScreen(
            CopperBeaconScreenHandler handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
        backgroundWidth = CopperBeaconScreenHandler.WIDTH;
        backgroundHeight = BACKGROUND_HEIGHT;
        playerInventoryTitleX = CopperBeaconScreenHandler.PLAYER_INVENTORY_X;
        playerInventoryTitleY = CopperBeaconScreenHandler.PLAYER_INVENTORY_Y - 12;
        titleX = 8;
        titleY = 6;
    }

    @Override
    protected void init() {
        super.init();
        // A side-mounted panel leaves the crosshair area unobstructed so the
        // articulated beacon remains visible while its controls are adjusted.
        if (width >= backgroundWidth + 16) {
            x = 8;
        }
        buildControls();
    }

    private void buildControls() {
        CopperBeaconBlockEntity beacon = handler.getBeacon();
        if (beacon == null) {
            return;
        }

        addSlider(
                x + 56,
                y + 17,
                94,
                "container.rtxremix.copper_beacon.saturation",
                beacon.getSaturation(),
                value -> value / CopperBeaconBlockEntity.MAX_SATURATION,
                value -> value * CopperBeaconBlockEntity.MAX_SATURATION,
                value -> Component.literal(String.format(Locale.ROOT, "%.0f%%", value * 100.0)),
                CopperBeaconUpdatePayload.Field.SATURATION);

        addDrawableChild(Checkbox.builder(
                        Component.translatable(
                                "container.rtxremix.copper_beacon.redstone"),
                        textRenderer)
                .pos(x + 158, y + 17)
                .checked(beacon.isRedstoneControlled())
                .withTooltip(Tooltip.create(Component.translatable(
                        "container.rtxremix.copper_beacon.redstone.tip")))
                .callback((checkbox, checked) -> queue(
                        CopperBeaconUpdatePayload.Field.REDSTONE_CONTROLLED,
                        checked ? 1.0f : 0.0f))
                .build());

        addSlider(
                x + OUTER_MARGIN,
                y + 36,
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
                x + 134,
                y + 36,
                118,
                "container.rtxremix.copper_beacon.cone",
                beacon.getConeAngle(),
                value -> value / CopperBeaconBlockEntity.MAX_CONE_ANGLE,
                value -> value * CopperBeaconBlockEntity.MAX_CONE_ANGLE,
                value -> Component.literal(String.format(Locale.ROOT, "%.1f\u00b0", value)),
                CopperBeaconUpdatePayload.Field.CONE_ANGLE);

        addAxisControls(
                x + OUTER_MARGIN,
                y + 54,
                beacon.getYaw(),
                true);
        addAxisControls(
                x + OUTER_MARGIN + PANEL_WIDTH + PANEL_GAP,
                y + 54,
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

        addDrawableChild(CycleButton
                .<CopperBeaconAnimationMode>builder(CopperBeaconScreen::modeText)
                .withValues(CopperBeaconAnimationMode.withValues())
                .withInitialValue(state.mode())
                .build(
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
        clearChildren();
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
        addDrawableChild(new BeaconSlider(
                x,
                y,
                width,
                Component.translatable(labelKey),
                Component.translatable(labelKey + ".tip"),
                Math.clamp(toSlider.applyAsDouble(current), 0.0, 1.0),
                fromSlider,
                valueText,
                value -> queue(field, value.getAsFloat()),
                this::flushPending));
    }

    private void queue(CopperBeaconUpdatePayload.Field field, float value) {
        handler.applyUpdate(field, value);
        pending.put(field, value);
    }

    private void flushPending() {
        if (!ClientPlayNetworking.canSend(CopperBeaconUpdatePayload.ID)) {
            pending.clear();
            return;
        }
        pending.forEach((field, value) -> ClientPlayNetworking.send(
                new CopperBeaconUpdatePayload(handler.syncId, field, value)));
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
        super.render(context, mouseX, mouseY, delta);
        drawMouseoverTooltip(context, mouseX, mouseY);
    }

    @Override
    protected void renderBg(
            GuiGraphics context, float delta, int mouseX, int mouseY) {
        context.renderOutline(x, y, backgroundWidth, backgroundHeight, 0xFF8B8B8B);
        for (var slot : handler.slots) {
            context.drawGuiTexture(
                    SLOT_TEXTURE,
                    x + slot.x - 1,
                    y + slot.y - 1,
                    18,
                    18);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics context, int mouseX, int mouseY) {
        context.drawString(
                textRenderer,
                title,
                titleX,
                titleY,
                0xFFE0E0E0,
                false);
        context.drawString(
                textRenderer,
                playerInventoryTitle,
                playerInventoryTitleX,
                playerInventoryTitleY,
                0xFFE0E0E0,
                false);
        context.drawString(
                textRenderer,
                Component.translatable("container.rtxremix.copper_beacon.yaw"),
                OUTER_MARGIN,
                55,
                0xFFE0E0E0,
                false);
        context.drawString(
                textRenderer,
                Component.translatable("container.rtxremix.copper_beacon.pitch"),
                OUTER_MARGIN + PANEL_WIDTH + PANEL_GAP,
                55,
                0xFFE0E0E0,
                false);
        context.drawString(
                textRenderer,
                Component.translatable("container.rtxremix.copper_beacon.dye"),
                OUTER_MARGIN,
                22,
                0xFFE0E0E0,
                false);
    }

    private static Component modeText(CopperBeaconAnimationMode mode) {
        return Component.translatable(
                "container.rtxremix.copper_beacon.mode."
                        + mode.name().lowercaseVariant(Locale.ROOT));
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
            setMessage(CommonComponents.composeGenericOptionText(label, valueText.apply(actual)));
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

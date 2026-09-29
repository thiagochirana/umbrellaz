package dev.chirana.umbrellaz.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Objects;

public final class LockPromptScreen extends Screen {
    private static final int BACKDROP_TOP = 0xB8000000;
    private static final int BACKDROP_BOTTOM = 0xD6000000;
    private static final int PANEL_SHADOW = 0x99000000;
    private static final int PANEL_EDGE = 0xFF6B6B6B;
    private static final int PANEL_EDGE_DARK = 0xFF2B2B2B;
    private static final int PANEL = 0xFF111111;
    private static final int PANEL_TOP = 0xFF1B1B1B;
    private static final int GOLD = 0xFFFFAA00;
    private static final int VALID = 0xFF55FF55;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_MUTED = 0xFFAAAAAA;
    private static final int FIELD = 0xFF080808;
    private static final int FIELD_DISABLED = 0xFF151515;
    private static final int ERROR = 0xFFFF5555;
    private static final int BUTTON_DARK = 0xFF3A342C;
    private static final int BUTTON_HOVER = 0xFF544A3D;
    private static final int BUTTON_PRESSED = 0xFF27231E;
    private static final int SUBMIT = 0xFF2E6B2E;
    private static final int SUBMIT_HOVER = 0xFF3C873C;
    private static final int SUBMIT_PRESSED = 0xFF245324;

    private final LockPromptSubmissionHandler submissionHandler;
    private final Runnable cancelHandler;
    private final Runnable completionHandler;
    private final LockPromptAccessibleControl inputAccessibility = accessibleControl(LockPromptFocus.INPUT);
    private final LockPromptAccessibleControl submitAccessibility = accessibleControl(LockPromptFocus.SUBMIT);
    private final LockPromptAccessibleControl cancelAccessibility = accessibleControl(LockPromptFocus.CANCEL);

    private LockPromptModel model;
    private LockPromptLayout layout = LockPromptLayout.calculate(0, 0);
    private PressTarget pressedTarget = PressTarget.NONE;
    private int ticks;
    private int cooldownTicks;
    private boolean dismissed;

    public LockPromptScreen(
            LockPromptMode mode,
            LockPromptSubmissionHandler submissionHandler,
            Runnable cancelHandler,
            Runnable completionHandler
    ) {
        super(Component.literal("Cadeado"));
        this.model = LockPromptModel.create(Objects.requireNonNull(mode));
        this.submissionHandler = Objects.requireNonNull(submissionHandler);
        this.cancelHandler = Objects.requireNonNull(cancelHandler);
        this.completionHandler = Objects.requireNonNull(completionHandler);
    }

    @Override
    protected void init() {
        addWidget(inputAccessibility);
        addWidget(submitAccessibility);
        addWidget(cancelAccessibility);
        updateLayout();
    }

    @Override
    protected void setInitialFocus() {
        syncAccessibleFocus();
    }

    @Override
    protected void repositionElements() {
        boolean wasBelowMinimum = layout.belowMinimum();
        updateLayout();
        if (wasBelowMinimum != layout.belowMinimum()) {
            scheduleNarration();
        }
    }

    @Override
    public void tick() {
        ticks++;
        if (model.status() == LockPromptStatus.COOLDOWN && ++cooldownTicks >= 20) {
            cooldownTicks = 0;
            LockPromptStatus previousStatus = model.status();
            model = model.tickCooldown();
            if (previousStatus != model.status()) {
                syncAccessibleFocus();
                triggerImmediateNarration(false);
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, BACKDROP_TOP, BACKDROP_BOTTOM);
        updateAccessibleHover(mouseX, mouseY);

        UiRect panel = layout.panel();
        if (layout.belowMinimum()) {
            drawMinimumFallback(graphics, panel);
            return;
        }

        drawPanel(graphics, panel);
        drawHeader(graphics);
        drawInput(graphics, mouseX, mouseY);
        drawMessage(graphics);
        drawButton(graphics, layout.cancel(), "Cancelar", model.focus() == LockPromptFocus.CANCEL,
                layout.cancel().contains(mouseX, mouseY), pressedTarget == PressTarget.CANCEL, false, true);
        drawButton(graphics, layout.submit(), submitLabel(), model.focus() == LockPromptFocus.SUBMIT,
                layout.submit().contains(mouseX, mouseY), pressedTarget == PressTarget.SUBMIT,
                model.canSubmit(), false);

        if (layout.input().contains(mouseX, mouseY) && model.canEdit()) {
            graphics.requestCursor(CursorTypes.IBEAM);
        } else if (layout.cancel().contains(mouseX, mouseY)
                || layout.submit().contains(mouseX, mouseY) && model.canSubmit()) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || layout.belowMinimum()) {
            return event.button() == InputConstants.MOUSE_BUTTON_LEFT;
        }
        if (layout.input().contains(event.x(), event.y()) && model.canEdit()) {
            model = model.focus(LockPromptFocus.INPUT);
            if (doubleClick && !model.password().isEmpty()) {
                model = model.moveCursorTo(0, false).moveCursorTo(model.password().length(), true);
            } else {
                model = model.moveCursorTo(cursorAt(event.x()), false);
            }
            syncAccessibleFocus();
            pressedTarget = PressTarget.INPUT;
            return true;
        }
        if (layout.submit().contains(event.x(), event.y())) {
            if (model.canSubmit()) {
                model = model.focus(LockPromptFocus.SUBMIT);
                syncAccessibleFocus();
                pressedTarget = PressTarget.SUBMIT;
            }
            return true;
        }
        if (layout.cancel().contains(event.x(), event.y())) {
            model = model.focus(LockPromptFocus.CANCEL);
            syncAccessibleFocus();
            pressedTarget = PressTarget.CANCEL;
            return true;
        }
        pressedTarget = PressTarget.NONE;
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        PressTarget released = pressedTarget;
        pressedTarget = PressTarget.NONE;
        if (released == PressTarget.SUBMIT && layout.submit().contains(event.x(), event.y())) {
            submit();
            return true;
        }
        if (released == PressTarget.CANCEL && layout.cancel().contains(event.x(), event.y())) {
            cancel();
            return true;
        }
        return released == PressTarget.INPUT;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean selecting = (event.modifiers() & InputConstants.MOD_SHIFT) != 0;
        if (event.key() == InputConstants.KEY_ESCAPE) {
            cancel();
            return true;
        }
        if (layout.belowMinimum()) {
            return true;
        }
        return switch (event.key()) {
            case InputConstants.KEY_TAB -> {
                model = model.tab(selecting, false);
                syncAccessibleFocus();
                yield true;
            }
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                if (model.focus() == LockPromptFocus.CANCEL) {
                    cancel();
                } else {
                    submit();
                }
                yield true;
            }
            case InputConstants.KEY_BACKSPACE -> {
                model = model.backspace();
                yield true;
            }
            case InputConstants.KEY_DELETE -> {
                model = model.deleteForward();
                yield true;
            }
            case InputConstants.KEY_LEFT -> {
                model = model.moveCursor(-1, selecting);
                yield true;
            }
            case InputConstants.KEY_RIGHT -> {
                model = model.moveCursor(1, selecting);
                yield true;
            }
            case InputConstants.KEY_HOME -> {
                model = model.moveCursorTo(0, selecting);
                yield true;
            }
            case InputConstants.KEY_END -> {
                model = model.moveCursorTo(model.password().length(), selecting);
                yield true;
            }
            default -> super.keyPressed(event);
        };
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (layout.belowMinimum()) {
            return true;
        }
        return insertCharacter(event);
    }

    @Override
    public void onClose() {
        cancel();
    }

    @Override
    public void removed() {
        setFocused(null);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void updateNarrationState(NarrationElementOutput output) {
        LockPromptNarration narration = LockPromptNarration.from(model, layout.belowMinimum());
        output.add(NarratedElementType.TITLE, Component.literal(narration.title()));
        if (layout.belowMinimum()) {
            output.add(NarratedElementType.POSITION, Component.literal(narration.focus()));
            output.add(NarratedElementType.HINT, Component.literal(narration.status()));
            output.add(NarratedElementType.USAGE, Component.literal(narration.actions()));
        } else {
            updateNarratedWidget(output);
        }
    }

    public LockPromptMode mode() {
        return model.mode();
    }

    public LockPromptStatus status() {
        return model.status();
    }

    public void showError(String message) {
        if (isActiveSubmission()) {
            model = model.showError(message);
            syncAccessibleFocus();
            triggerImmediateNarration(false);
        }
    }

    public void showCooldown(int seconds) {
        if (isActiveSubmission()) {
            cooldownTicks = 0;
            model = model.startCooldown(seconds);
            syncAccessibleFocus();
            triggerImmediateNarration(false);
        }
    }

    public void completeAndClose() {
        if (!isActiveSubmission()) {
            return;
        }
        dismissed = true;
        model = model.close();
        setFocused(null);
        completionHandler.run();
    }

    private void drawPanel(GuiGraphicsExtractor graphics, UiRect panel) {
        graphics.fill(RenderPipelines.GUI, panel.x() + 4, panel.y() + 5,
                panel.right() + 4, panel.bottom() + 5, PANEL_SHADOW);
        graphics.fill(RenderPipelines.GUI, panel.x(), panel.y(), panel.right(), panel.bottom(), PANEL);
        graphics.fillGradient(panel.x(), panel.y(), panel.right(), panel.y() + Math.min(34, panel.height()),
                PANEL_TOP, PANEL);
        graphics.outline(panel.x(), panel.y(), panel.width(), panel.height(), PANEL_EDGE);
        graphics.outline(panel.x() + 1, panel.y() + 1, panel.width() - 2, panel.height() - 2, PANEL_EDGE_DARK);
        graphics.fill(RenderPipelines.GUI, panel.x() + 2, panel.y() + 2,
                Math.min(panel.right() - 2, panel.x() + 46), panel.y() + 3, GOLD);
    }

    private void drawHeader(GuiGraphicsExtractor graphics) {
        UiRect title = layout.title();
        graphics.text(font, model.mode().eyebrow(), title.x(), title.y(), GOLD, false);
    }

    private void drawInput(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        UiRect input = layout.input();
        boolean editable = model.canEdit();
        boolean focused = model.focus() == LockPromptFocus.INPUT;
        boolean hovered = input.contains(mouseX, mouseY);
        String counter = model.password().length() + "/" + LockPromptModel.MAX_LENGTH;
        graphics.text(font, "SENHA", input.x(), input.y() - 11, TEXT_MUTED, false);
        graphics.text(font, counter, input.right() - font.width(counter), input.y() - 12,
                model.isPolicyValid() ? VALID : TEXT_MUTED, false);
        int fieldColor = editable ? FIELD : FIELD_DISABLED;
        graphics.fill(RenderPipelines.GUI, input.x(), input.y(), input.right(), input.bottom(), fieldColor);
        graphics.outline(input.x(), input.y(), input.width(), input.height(),
                focused ? GOLD : hovered && editable ? PANEL_EDGE : PANEL_EDGE_DARK);
        if (focused) {
            graphics.outline(input.x() - 2, input.y() - 2, input.width() + 4, input.height() + 4,
                    0x66FFAA00);
        }

        int innerWidth = input.width() - 16;
        int gap = layout.density() == LockPromptLayout.Density.STANDARD ? 6 : 4;
        int slotWidth = Math.max(12, (innerWidth - gap * 3) / 4);
        int slotsWidth = slotWidth * 4 + gap * 3;
        int slotsX = input.x() + (input.width() - slotsWidth) / 2;
        int slotY = input.y() + 7;
        int slotHeight = input.height() - 14;

        for (int index = 0; index < 4; index++) {
            int slotX = slotsX + index * (slotWidth + gap);
            boolean selected = model.hasSelection()
                    && index >= model.selectionStart()
                    && index < model.selectionEnd();
            if (selected) {
                graphics.fill(RenderPipelines.GUI, slotX, slotY, slotX + slotWidth, slotY + slotHeight,
                        0x66555555);
            }
            graphics.fill(RenderPipelines.GUI, slotX, slotY + slotHeight - 2,
                    slotX + slotWidth, slotY + slotHeight,
                    index < model.password().length() ? GOLD : 0xFF444444);
            if (index < model.password().length()) {
                centeredText(graphics, "•", slotX, slotY + (slotHeight - font.lineHeight) / 2,
                        slotWidth, TEXT);
            }
        }

        if (focused && editable && (ticks / 10) % 2 == 0) {
            int position = Math.min(4, model.cursor());
            int cursorX = position == 4
                    ? slotsX + slotsWidth
                    : slotsX + position * (slotWidth + gap);
            graphics.fill(RenderPipelines.GUI, cursorX, slotY + 2, cursorX + 1,
                    slotY + slotHeight - 3, GOLD);
        }
    }

    private void drawMessage(GuiGraphicsExtractor graphics) {
        UiRect message = layout.message();
        String text;
        int color;
        if (!model.message().isEmpty()) {
            text = model.message();
            color = model.status() == LockPromptStatus.ERROR || model.status() == LockPromptStatus.COOLDOWN
                    ? ERROR
                    : TEXT_MUTED;
        } else if (model.isPolicyValid()) {
            text = "Senha pronta.";
            color = VALID;
        } else {
            text = "Formato: A123";
            color = TEXT_MUTED;
        }
        List<FormattedCharSequence> lines = font.split(Component.literal(text), message.width());
        int maxLines = Math.max(1, message.height() / font.lineHeight);
        for (int index = 0; index < Math.min(lines.size(), maxLines); index++) {
            graphics.text(font, lines.get(index), message.x(), message.y() + index * font.lineHeight, color, false);
        }
    }

    private void drawButton(
            GuiGraphicsExtractor graphics,
            UiRect bounds,
            String label,
            boolean focused,
            boolean hovered,
            boolean pressed,
            boolean enabled,
            boolean secondary
    ) {
        boolean active = secondary || enabled;
        int background;
        if (!active) {
            background = 0xFF222222;
        } else if (pressed) {
            background = secondary ? BUTTON_PRESSED : SUBMIT_PRESSED;
        } else if (hovered) {
            background = secondary ? BUTTON_HOVER : SUBMIT_HOVER;
        } else {
            background = secondary ? BUTTON_DARK : SUBMIT;
        }
        graphics.fill(RenderPipelines.GUI, bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), background);
        graphics.outline(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                focused ? GOLD : secondary ? PANEL_EDGE : active ? VALID : PANEL_EDGE_DARK);
        if (focused) {
            graphics.fill(RenderPipelines.GUI, bounds.x() + 3, bounds.bottom() - 2,
                    bounds.right() - 3, bounds.bottom() - 1, GOLD);
        }
        int color = active ? TEXT : 0xFF666666;
        centeredText(graphics, label, bounds.x(), bounds.y() + (bounds.height() - font.lineHeight) / 2,
                bounds.width(), color);
    }

    private void drawMinimumFallback(GuiGraphicsExtractor graphics, UiRect panel) {
        graphics.fill(RenderPipelines.GUI, panel.x(), panel.y(), panel.right(), panel.bottom(), PANEL);
        graphics.outline(panel.x(), panel.y(), panel.width(), panel.height(), PANEL_EDGE);
        int centerY = Math.max(panel.y() + 8, panel.y() + panel.height() / 2 - font.lineHeight);
        centeredText(graphics, "Cadeado", panel.x(), centerY, panel.width(), TEXT);
        centeredText(graphics, "Aumente a janela para continuar.", panel.x(), centerY + font.lineHeight + 4,
                panel.width(), TEXT_MUTED);
    }

    private void centeredText(GuiGraphicsExtractor graphics, String text, int x, int y, int width, int color) {
        graphics.text(font, text, x + Math.max(0, (width - font.width(text)) / 2), y, color, false);
    }

    private int cursorAt(double mouseX) {
        UiRect input = layout.input();
        double relative = Math.max(0.0, Math.min(input.width(), mouseX - input.x()));
        return Math.max(0, Math.min(model.password().length(),
                (int) Math.round(relative / input.width() * LockPromptModel.MAX_LENGTH)));
    }

    private String submitLabel() {
        return model.status() == LockPromptStatus.SUBMITTING
                ? "Enviando…"
                : model.mode().submitLabel();
    }

    private void submit() {
        LockPromptModel submitted = model.submit(!layout.belowMinimum());
        if (submitted.status() != LockPromptStatus.SUBMITTING
                || model.status() == LockPromptStatus.SUBMITTING) {
            model = submitted;
            syncAccessibleFocus();
            return;
        }
        model = submitted;
        syncAccessibleFocus();
        try {
            submissionHandler.submit(model.mode(), model.password(), new Completion());
        } catch (RuntimeException exception) {
            model = model.showError("Não foi possível preparar o pedido.");
            syncAccessibleFocus();
            triggerImmediateNarration(false);
        }
    }

    private void cancel() {
        if (dismissed) {
            return;
        }
        dismissed = true;
        model = model.close();
        setFocused(null);
        cancelHandler.run();
    }

    private boolean isActiveSubmission() {
        return !dismissed && model.status() == LockPromptStatus.SUBMITTING;
    }

    private void updateLayout() {
        layout = LockPromptLayout.calculate(width, height);
        pressedTarget = PressTarget.NONE;
        model = model.normalizeFocus(layout.belowMinimum());
        syncAccessibleFocus();
    }

    private LockPromptAccessibleControl accessibleControl(LockPromptFocus control) {
        return new LockPromptAccessibleControl(
                control,
                () -> !layout.belowMinimum() && model.canFocus(control, false),
                () -> switch (control) {
                    case INPUT -> layout.input();
                    case SUBMIT -> layout.submit();
                    case CANCEL -> layout.cancel();
                },
                () -> LockPromptNarration.forControl(model, control),
                focusedControl -> model = model.focus(focusedControl),
                control == LockPromptFocus.INPUT ? this::insertCharacter : event -> false);
    }

    private boolean insertCharacter(CharacterEvent event) {
        if (model.focus() != LockPromptFocus.INPUT || !model.canEdit()) {
            return false;
        }
        LockPromptModel updated = model.insert(event.codepointAsString());
        boolean consumed = updated != model;
        model = updated;
        return consumed;
    }

    private void syncAccessibleFocus() {
        if (layout.belowMinimum()) {
            setFocused(null);
            return;
        }
        LockPromptAccessibleControl focused = switch (model.focus()) {
            case INPUT -> inputAccessibility;
            case SUBMIT -> submitAccessibility;
            case CANCEL -> cancelAccessibility;
        };
        setFocused(focused.isActive() ? focused : null);
    }

    private void updateAccessibleHover(int mouseX, int mouseY) {
        inputAccessibility.setHovered(inputAccessibility.isMouseOver(mouseX, mouseY));
        submitAccessibility.setHovered(submitAccessibility.isMouseOver(mouseX, mouseY));
        cancelAccessibility.setHovered(cancelAccessibility.isMouseOver(mouseX, mouseY));
    }

    private enum PressTarget {
        NONE,
        INPUT,
        SUBMIT,
        CANCEL
    }

    private final class Completion implements LockPromptCompletion {
        @Override
        public void error(String message) {
            minecraft.execute(() -> {
                if (minecraft.gui.screen() == LockPromptScreen.this) {
                    showError(message);
                }
            });
        }

        @Override
        public void cooldown(int seconds) {
            minecraft.execute(() -> {
                if (minecraft.gui.screen() == LockPromptScreen.this) {
                    showCooldown(seconds);
                }
            });
        }

        @Override
        public void close() {
            minecraft.execute(() -> {
                if (minecraft.gui.screen() == LockPromptScreen.this) {
                    completeAndClose();
                }
            });
        }
    }
}

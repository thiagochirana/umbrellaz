package dev.chirana.umbrellaz.gui;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class LockPromptAccessibleControl implements GuiEventListener, NarratableEntry {
    private final LockPromptFocus control;
    private final BooleanSupplier active;
    private final Supplier<UiRect> bounds;
    private final Supplier<LockPromptNarration> narration;
    private final Consumer<LockPromptFocus> focusRequest;
    private final Predicate<CharacterEvent> characterTyped;
    private final Consumer<Boolean> textInputFocusChange;

    private boolean focused;
    private boolean hovered;

    LockPromptAccessibleControl(
            LockPromptFocus control,
            BooleanSupplier active,
            Supplier<UiRect> bounds,
            Supplier<LockPromptNarration> narration,
            Consumer<LockPromptFocus> focusRequest,
            Predicate<CharacterEvent> characterTyped
    ) {
        this(control, active, bounds, narration, focusRequest, characterTyped, null);
    }

    LockPromptAccessibleControl(
            LockPromptFocus control,
            BooleanSupplier active,
            Supplier<UiRect> bounds,
            Supplier<LockPromptNarration> narration,
            Consumer<LockPromptFocus> focusRequest,
            Predicate<CharacterEvent> characterTyped,
            Consumer<Boolean> textInputFocusChange
    ) {
        this.control = Objects.requireNonNull(control);
        this.active = Objects.requireNonNull(active);
        this.bounds = Objects.requireNonNull(bounds);
        this.narration = Objects.requireNonNull(narration);
        this.focusRequest = Objects.requireNonNull(focusRequest);
        this.characterTyped = Objects.requireNonNull(characterTyped);
        this.textInputFocusChange = textInputFocusChange != null
                ? textInputFocusChange
                : focused -> Minecraft.getInstance().onTextInputFocusChange(this, focused);
    }

    @Override
    public void setFocused(boolean focused) {
        boolean nextFocused = focused && isActive();
        if (this.focused != nextFocused) {
            this.focused = nextFocused;
            if (control == LockPromptFocus.INPUT) {
                textInputFocusChange.accept(nextFocused);
            }
        }
        if (nextFocused) {
            focusRequest.accept(control);
        }
    }

    @Override
    public boolean isFocused() {
        return focused;
    }

    @Override
    public boolean isActive() {
        boolean activeNow = active.getAsBoolean();
        if (!activeNow && focused) {
            focused = false;
            if (control == LockPromptFocus.INPUT) {
                textInputFocusChange.accept(false);
            }
        }
        return activeNow;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!isActive() || !focused || control != LockPromptFocus.INPUT) {
            return false;
        }
        return characterTyped.test(event);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return isActive() && bounds.get().contains(mouseX, mouseY);
    }

    @Override
    public ScreenRectangle getRectangle() {
        UiRect rectangle = bounds.get();
        return new ScreenRectangle(rectangle.x(), rectangle.y(), rectangle.width(), rectangle.height());
    }

    @Override
    public int getTabOrderGroup() {
        return control.ordinal();
    }

    @Override
    public NarrationPriority narrationPriority() {
        if (!isActive()) {
            return NarrationPriority.NONE;
        }
        if (focused) {
            return NarrationPriority.FOCUSED;
        }
        return hovered ? NarrationPriority.HOVERED : NarrationPriority.NONE;
    }

    @Override
    public void updateNarration(NarrationElementOutput output) {
        LockPromptNarration content = narration.get();
        output.add(NarratedElementType.TITLE, Component.literal(content.title()));
        output.add(NarratedElementType.POSITION, Component.literal(content.focus()));
        output.add(NarratedElementType.HINT, Component.literal(content.status()));
        output.add(NarratedElementType.USAGE, Component.literal(content.actions()));
    }

    void setHovered(boolean hovered) {
        this.hovered = hovered && isActive();
    }
}

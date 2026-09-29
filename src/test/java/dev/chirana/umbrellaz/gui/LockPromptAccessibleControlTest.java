package dev.chirana.umbrellaz.gui;

import net.minecraft.client.input.CharacterEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockPromptAccessibleControlTest {
    @Test
    void routesCharacterEventsToInputOnlyWhileInputIsFocused() {
        LockPromptModel[] state = {
                LockPromptModel.create(LockPromptMode.CREATE_PASSWORD)
        };
        LockPromptAccessibleControl input = control(LockPromptFocus.INPUT, state);
        LockPromptAccessibleControl submit = control(LockPromptFocus.SUBMIT, state);
        LockPromptAccessibleControl cancel = control(LockPromptFocus.CANCEL, state);

        input.setFocused(true);
        for (int codepoint : new int[]{'A', '1', '2', '3'}) {
            assertTrue(input.charTyped(new CharacterEvent(codepoint)));
        }
        assertEquals("A123", state[0].password());

        submit.setFocused(true);
        assertFalse(submit.charTyped(new CharacterEvent('9')));
        assertEquals("A123", state[0].password());

        cancel.setFocused(true);
        assertFalse(cancel.charTyped(new CharacterEvent('9')));
        assertEquals("A123", state[0].password());
    }

    @Test
    void togglesNativeTextInputOnlyWhenInputFocusChanges() {
        LockPromptModel[] state = {
                LockPromptModel.create(LockPromptMode.CREATE_PASSWORD)
        };
        List<Boolean> nativeFocus = new ArrayList<>();
        LockPromptAccessibleControl input = control(LockPromptFocus.INPUT, state, nativeFocus::add);
        LockPromptAccessibleControl submit = control(LockPromptFocus.SUBMIT, state, ignored -> {
        });
        LockPromptAccessibleControl cancel = control(LockPromptFocus.CANCEL, state, ignored -> {
        });

        input.setFocused(true);
        input.setFocused(true);
        input.setFocused(false);
        submit.setFocused(true);
        submit.setFocused(true);
        submit.setFocused(false);
        cancel.setFocused(true);
        cancel.setFocused(false);

        assertEquals(List.of(true, false), nativeFocus);
    }

    @Test
    void deactivatingInputReleasesNativeTextInputWithoutReactivation() {
        LockPromptModel[] state = {
                LockPromptModel.create(LockPromptMode.CREATE_PASSWORD)
        };
        boolean[] active = {true};
        List<Boolean> nativeFocus = new ArrayList<>();
        LockPromptAccessibleControl input = new LockPromptAccessibleControl(
                LockPromptFocus.INPUT,
                () -> active[0] && state[0].canFocus(LockPromptFocus.INPUT, false),
                () -> new UiRect(0, 0, 100, 20),
                () -> LockPromptNarration.forControl(state[0], LockPromptFocus.INPUT),
                requested -> state[0] = state[0].focus(requested),
                event -> false,
                nativeFocus::add
        );

        input.setFocused(true);
        active[0] = false;
        assertFalse(input.isActive());
        input.setFocused(false);

        assertEquals(List.of(true, false), nativeFocus);
    }

    @Test
    void closingFocusedInputReleasesNativeTextInput() {
        LockPromptModel[] state = {
                LockPromptModel.create(LockPromptMode.CREATE_PASSWORD)
        };
        List<Boolean> nativeFocus = new ArrayList<>();
        LockPromptAccessibleControl input = control(LockPromptFocus.INPUT, state, nativeFocus::add);

        input.setFocused(true);
        state[0] = state[0].close();
        assertFalse(input.isActive());
        input.setFocused(false);

        assertEquals(List.of(true, false), nativeFocus);
    }

    private static LockPromptAccessibleControl control(
            LockPromptFocus focus,
            LockPromptModel[] state
    ) {
        return control(focus, state, ignored -> {
        });
    }

    private static LockPromptAccessibleControl control(
            LockPromptFocus focus,
            LockPromptModel[] state,
            java.util.function.Consumer<Boolean> nativeFocus
    ) {
        return new LockPromptAccessibleControl(
                focus,
                () -> state[0].canFocus(focus, false),
                () -> new UiRect(0, 0, 100, 20),
                () -> LockPromptNarration.forControl(state[0], focus),
                requested -> state[0] = state[0].focus(requested),
                event -> {
                    LockPromptModel updated = state[0].insert(event.codepointAsString());
                    boolean consumed = updated != state[0];
                    state[0] = updated;
                    return consumed;
                },
                nativeFocus
        );
    }
}

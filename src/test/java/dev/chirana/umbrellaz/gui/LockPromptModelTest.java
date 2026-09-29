package dev.chirana.umbrellaz.gui;

import net.minecraft.client.input.CharacterEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockPromptModelTest {
    @Test
    void exposesModeSpecificPortugueseCopy() {
        assertEquals("Cadeado Umbrellaz", LockPromptMode.CREATE_PASSWORD.title());
        assertEquals("Criar cadeado", LockPromptMode.CREATE_PASSWORD.submitLabel());
        assertEquals("Confirmar", LockPromptMode.CONFIRM_PASSWORD.submitLabel());
        assertTrue(LockPromptMode.CONFIRM_PASSWORD.passwordHint().contains("primeiro"));
        assertFalse(LockPromptMode.CONFIRM_PASSWORD.passwordHint().contains("qualquer ordem"));
    }

    @Test
    void filtersToAsciiLettersAndDigitsAndEnforcesMaxLength() {
        LockPromptModel model = LockPromptModel.create(LockPromptMode.CREATE_PASSWORD)
                .insert("áA-12_3Z9");

        assertEquals("A123", model.password());
        assertEquals(4, model.cursor());
        assertTrue(model.isPolicyValid());
        assertEquals("••••", model.maskedPassword());
    }

    @Test
    void acceptsRepresentativeCharacterEventsWhileInputIsFocused() {
        LockPromptModel model = LockPromptModel.create(LockPromptMode.CREATE_PASSWORD);
        for (int codepoint : new int[]{'A', '1', '2', '3'}) {
            model = model.insert(new CharacterEvent(codepoint).codepointAsString());
        }

        assertEquals("A123", model.password());
    }

    @Test
    void rejectsCharacterEventsWhenSubmitOrCancelIsFocused() {
        LockPromptModel input = LockPromptModel.create(LockPromptMode.CREATE_PASSWORD)
                .insert("A123");
        LockPromptModel submit = input.focus(LockPromptFocus.SUBMIT);
        LockPromptModel cancel = input.focus(LockPromptFocus.CANCEL);

        assertEquals(submit, submit.insert(new CharacterEvent('9').codepointAsString()));
        assertEquals(cancel, cancel.insert(new CharacterEvent('9').codepointAsString()));
    }

    @Test
    void supportsSelectionReplacementAndDeletion() {
        LockPromptModel model = LockPromptModel.create(LockPromptMode.CREATE_PASSWORD)
                .insert("A123")
                .moveCursorTo(1, false)
                .moveCursorTo(3, true)
                .insert("9");

        assertEquals("A93", model.password());
        assertEquals(2, model.cursor());
        assertFalse(model.hasSelection());
        assertEquals("A3", model.backspace().password());
    }

    @Test
    void cyclesFocusInBothDirections() {
        LockPromptModel model = LockPromptModel.create(LockPromptMode.CONFIRM_PASSWORD);

        assertEquals(LockPromptFocus.CANCEL, model.tab(false).focus());
        assertEquals(LockPromptFocus.CANCEL, model.tab(true).focus());

        LockPromptModel valid = model.insert("A123");
        assertEquals(LockPromptFocus.SUBMIT, valid.tab(false).focus());
        assertEquals(LockPromptFocus.SUBMIT,
                valid.focus(LockPromptFocus.CANCEL).tab(true).focus());
    }

    @Test
    void matchesAuthoritativeLetterFirstThenThreeDigitsPolicy() {
        assertTrue(LockPromptModel.create(LockPromptMode.CREATE_PASSWORD).insert("A123").isPolicyValid());
        assertTrue(LockPromptModel.create(LockPromptMode.CREATE_PASSWORD).insert("z987").isPolicyValid());

        assertFalse(LockPromptModel.create(LockPromptMode.CREATE_PASSWORD).insert("1A23").isPolicyValid());
        assertFalse(LockPromptModel.create(LockPromptMode.CREATE_PASSWORD).insert("12A3").isPolicyValid());
        assertFalse(LockPromptModel.create(LockPromptMode.CREATE_PASSWORD).insert("123A").isPolicyValid());

        LockPromptModel misplaced = LockPromptModel.create(LockPromptMode.CREATE_PASSWORD)
                .insert("1A23")
                .submit();
        assertEquals(LockPromptStatus.ERROR, misplaced.status());
        assertTrue(misplaced.message().contains("primeiro"));
    }

    @Test
    void editingKeysOnlyOperateWhileEditableInputIsFocused() {
        LockPromptModel input = LockPromptModel.create(LockPromptMode.CONFIRM_PASSWORD)
                .insert("A123")
                .moveCursorTo(2, false);
        LockPromptModel cancel = input.focus(LockPromptFocus.CANCEL);
        LockPromptModel selectedThenCancelled = input.moveCursorTo(0, false)
                .moveCursorTo(2, true)
                .focus(LockPromptFocus.CANCEL);

        assertEquals(cancel, cancel.insert("9"));
        assertEquals(cancel, cancel.backspace());
        assertEquals(cancel, cancel.deleteForward());
        assertEquals(cancel, cancel.moveCursor(-1, false));
        assertEquals(cancel, cancel.moveCursorTo(0, false));
        assertEquals(selectedThenCancelled, selectedThenCancelled.insert("9"));

        LockPromptModel submit = input.focus(LockPromptFocus.SUBMIT);
        assertEquals(submit, submit.insert("9"));
        assertEquals(submit, submit.backspace());
        assertEquals(submit, submit.moveCursorTo(0, true));
    }

    @Test
    void disabledAndBelowMinimumTraversalOnlyExposeCancellation() {
        LockPromptModel submitting = LockPromptModel.create(LockPromptMode.CREATE_PASSWORD)
                .insert("A123")
                .submit();
        assertEquals(LockPromptFocus.CANCEL, submitting.focus());
        assertEquals(LockPromptFocus.CANCEL, submitting.tab(false).focus());
        assertEquals(LockPromptFocus.CANCEL, submitting.tab(true).focus());

        LockPromptModel cooldown = LockPromptModel.create(LockPromptMode.CONFIRM_PASSWORD).startCooldown(3);
        assertEquals(LockPromptFocus.CANCEL, cooldown.tab(false).focus());

        LockPromptModel editing = LockPromptModel.create(LockPromptMode.CONFIRM_PASSWORD).insert("A123");
        assertEquals(LockPromptFocus.CANCEL, editing.tab(false, true).focus());
        assertEquals(editing, editing.submit(false));
        assertEquals(LockPromptFocus.CANCEL, editing.normalizeFocus(true).focus());
    }

    @Test
    void rejectsInvalidSubmitAndDisablesEditingWhileSubmitting() {
        LockPromptModel invalid = LockPromptModel.create(LockPromptMode.CREATE_PASSWORD).insert("AB12").submit();
        assertEquals(LockPromptStatus.ERROR, invalid.status());
        assertTrue(invalid.message().contains("1 letra"));

        LockPromptModel submitting = LockPromptModel.create(LockPromptMode.CREATE_PASSWORD)
                .insert("A123")
                .submit();
        assertEquals(LockPromptStatus.SUBMITTING, submitting.status());
        assertEquals("A123", submitting.insert("9").password());
        assertFalse(submitting.canEdit());
    }

    @Test
    void cancelAndCooldownAreTerminalOrBoundedTransitions() {
        LockPromptModel closed = LockPromptModel.create(LockPromptMode.CONFIRM_PASSWORD).close();
        assertEquals(LockPromptStatus.CLOSED, closed.status());
        assertEquals(LockPromptStatus.CLOSED, closed.tab(false).status());

        LockPromptModel cooldown = LockPromptModel.create(LockPromptMode.CONFIRM_PASSWORD).startCooldown(2);
        assertEquals(LockPromptStatus.COOLDOWN, cooldown.status());
        assertEquals(2, cooldown.cooldownSeconds());
        assertEquals(LockPromptStatus.COOLDOWN, cooldown.tickCooldown().status());
        assertEquals(LockPromptStatus.EDITING, cooldown.tickCooldown().tickCooldown().status());
    }

    @Test
    void boundsExternalMessages() {
        LockPromptModel model = LockPromptModel.create(LockPromptMode.CONFIRM_PASSWORD)
                .showError("x".repeat(300));

        assertEquals(LockPromptModel.MAX_MESSAGE_LENGTH, model.message().length());
    }
}

package dev.chirana.umbrellaz.gui;

import dev.chirana.umbrellaz.lock.PasswordPolicy;

import java.util.Objects;

public record LockPromptModel(
        LockPromptMode mode,
        LockPromptStatus status,
        String password,
        int cursor,
        int selectionAnchor,
        LockPromptFocus focus,
        String message,
        int cooldownSeconds
) {
    public static final int MAX_LENGTH = 4;
    public static final int MAX_MESSAGE_LENGTH = 96;
    private static final PasswordPolicy PASSWORD_POLICY = new PasswordPolicy();

    public LockPromptModel {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(focus, "focus");
        Objects.requireNonNull(message, "message");
        if (password.length() > MAX_LENGTH || cursor < 0 || cursor > password.length()
                || selectionAnchor < 0 || selectionAnchor > password.length() || cooldownSeconds < 0) {
            throw new IllegalArgumentException("Invalid lock prompt state");
        }
    }

    public static LockPromptModel create(LockPromptMode mode) {
        return new LockPromptModel(mode, LockPromptStatus.EDITING, "", 0, 0,
                LockPromptFocus.INPUT, "", 0);
    }

    public boolean canEdit() {
        return status == LockPromptStatus.EDITING || status == LockPromptStatus.ERROR;
    }

    public boolean canSubmit() {
        return canEdit() && isPolicyValid();
    }

    public boolean isPolicyValid() {
        return PASSWORD_POLICY.isValid(password);
    }

    public LockPromptModel insert(String text) {
        if (!canEditInput() || text == null || text.isEmpty()) {
            return this;
        }
        LockPromptModel result = this;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (isAsciiLetter(character) || isAsciiDigit(character)) {
                result = result.insertCharacter(character);
            }
        }
        return result;
    }

    public LockPromptModel backspace() {
        if (!canEditInput()) {
            return this;
        }
        if (hasSelection()) {
            return deleteSelection();
        }
        if (cursor == 0) {
            return editingCopy(password, cursor, cursor);
        }
        String updated = password.substring(0, cursor - 1) + password.substring(cursor);
        return editingCopy(updated, cursor - 1, cursor - 1);
    }

    public LockPromptModel deleteForward() {
        if (!canEditInput()) {
            return this;
        }
        if (hasSelection()) {
            return deleteSelection();
        }
        if (cursor == password.length()) {
            return editingCopy(password, cursor, cursor);
        }
        String updated = password.substring(0, cursor) + password.substring(cursor + 1);
        return editingCopy(updated, cursor, cursor);
    }

    public LockPromptModel moveCursor(int offset, boolean selecting) {
        if (!canEditInput()) {
            return this;
        }
        int destination = Math.max(0, Math.min(password.length(), cursor + offset));
        return moveCursorTo(destination, selecting);
    }

    public LockPromptModel moveCursorTo(int destination, boolean selecting) {
        if (!canEditInput()) {
            return this;
        }
        int clamped = Math.max(0, Math.min(password.length(), destination));
        return new LockPromptModel(mode, status, password, clamped,
                selecting ? selectionAnchor : clamped, focus, message, cooldownSeconds);
    }

    public LockPromptModel tab(boolean backwards) {
        return tab(backwards, false);
    }

    public LockPromptModel tab(boolean backwards, boolean belowMinimum) {
        if (status == LockPromptStatus.CLOSED) {
            return this;
        }
        LockPromptFocus nextFocus = nextFocus(backwards, belowMinimum);
        return new LockPromptModel(mode, status, password, cursor, selectionAnchor,
                nextFocus, message, cooldownSeconds);
    }

    public LockPromptModel focus(LockPromptFocus nextFocus) {
        if (status == LockPromptStatus.CLOSED) {
            return this;
        }
        return new LockPromptModel(mode, status, password, cursor, selectionAnchor,
                Objects.requireNonNull(nextFocus), message, cooldownSeconds);
    }

    public LockPromptModel normalizeFocus(boolean belowMinimum) {
        if (status == LockPromptStatus.CLOSED || canFocus(focus, belowMinimum)) {
            return this;
        }
        LockPromptFocus nextFocus = belowMinimum || !canEdit()
                ? LockPromptFocus.CANCEL
                : LockPromptFocus.INPUT;
        return focus(nextFocus);
    }

    public boolean canFocus(LockPromptFocus target, boolean belowMinimum) {
        if (status == LockPromptStatus.CLOSED) {
            return false;
        }
        if (belowMinimum || !canEdit()) {
            return target == LockPromptFocus.CANCEL;
        }
        return switch (target) {
            case INPUT, CANCEL -> true;
            case SUBMIT -> canSubmit();
        };
    }

    public LockPromptModel submit() {
        return submit(true);
    }

    public LockPromptModel submit(boolean interactionEnabled) {
        if (!interactionEnabled || !canEdit()) {
            return this;
        }
        if (!isPolicyValid()) {
            return new LockPromptModel(mode, LockPromptStatus.ERROR, password, cursor, selectionAnchor,
                    LockPromptFocus.INPUT, "Use 1 letra primeiro e depois 3 números.", 0);
        }
        return new LockPromptModel(mode, LockPromptStatus.SUBMITTING, password, cursor, selectionAnchor,
                LockPromptFocus.CANCEL, "Verificando pedido…", 0);
    }

    public LockPromptModel showError(String errorMessage) {
        if (status == LockPromptStatus.CLOSED) {
            return this;
        }
        return new LockPromptModel(mode, LockPromptStatus.ERROR, "", 0, 0,
                LockPromptFocus.INPUT, bounded(errorMessage, "Não foi possível continuar."), 0);
    }

    public LockPromptModel startCooldown(int seconds) {
        if (status == LockPromptStatus.CLOSED || seconds <= 0) {
            return this;
        }
        return new LockPromptModel(mode, LockPromptStatus.COOLDOWN, "", 0, 0,
                LockPromptFocus.CANCEL, cooldownMessage(seconds), seconds);
    }

    public LockPromptModel tickCooldown() {
        if (status != LockPromptStatus.COOLDOWN) {
            return this;
        }
        int remaining = Math.max(0, cooldownSeconds - 1);
        if (remaining == 0) {
            return new LockPromptModel(mode, LockPromptStatus.EDITING, "", 0, 0,
                    LockPromptFocus.INPUT, "Tente novamente.", 0);
        }
        return new LockPromptModel(mode, status, password, cursor, selectionAnchor,
                focus, cooldownMessage(remaining), remaining);
    }

    public LockPromptModel close() {
        return new LockPromptModel(mode, LockPromptStatus.CLOSED, password, cursor, selectionAnchor,
                focus, message, cooldownSeconds);
    }

    public boolean hasSelection() {
        return cursor != selectionAnchor;
    }

    public int selectionStart() {
        return Math.min(cursor, selectionAnchor);
    }

    public int selectionEnd() {
        return Math.max(cursor, selectionAnchor);
    }

    public String maskedPassword() {
        return "•".repeat(password.length());
    }

    private boolean canEditInput() {
        return canEdit() && focus == LockPromptFocus.INPUT;
    }

    private LockPromptFocus nextFocus(boolean backwards, boolean belowMinimum) {
        if (belowMinimum || !canEdit()) {
            return LockPromptFocus.CANCEL;
        }
        if (backwards) {
            return switch (focus) {
                case INPUT -> LockPromptFocus.CANCEL;
                case SUBMIT -> LockPromptFocus.INPUT;
                case CANCEL -> canSubmit() ? LockPromptFocus.SUBMIT : LockPromptFocus.INPUT;
            };
        }
        return switch (focus) {
            case INPUT -> canSubmit() ? LockPromptFocus.SUBMIT : LockPromptFocus.CANCEL;
            case SUBMIT -> LockPromptFocus.CANCEL;
            case CANCEL -> LockPromptFocus.INPUT;
        };
    }

    private LockPromptModel insertCharacter(char character) {
        int start = selectionStart();
        int end = selectionEnd();
        int resultingLength = password.length() - (end - start) + 1;
        if (resultingLength > MAX_LENGTH) {
            return this;
        }
        String updated = password.substring(0, start) + character + password.substring(end);
        int nextCursor = start + 1;
        return editingCopy(updated, nextCursor, nextCursor);
    }

    private LockPromptModel deleteSelection() {
        int start = selectionStart();
        int end = selectionEnd();
        String updated = password.substring(0, start) + password.substring(end);
        return editingCopy(updated, start, start);
    }

    private LockPromptModel editingCopy(String updatedPassword, int updatedCursor, int updatedAnchor) {
        LockPromptStatus nextStatus = status == LockPromptStatus.ERROR
                ? LockPromptStatus.EDITING
                : status;
        String nextMessage = status == LockPromptStatus.ERROR ? "" : message;
        return new LockPromptModel(mode, nextStatus, updatedPassword, updatedCursor, updatedAnchor,
                LockPromptFocus.INPUT, nextMessage, 0);
    }

    private static String bounded(String value, String fallback) {
        String resolved = value == null || value.isBlank() ? fallback : value.strip();
        return resolved.length() <= MAX_MESSAGE_LENGTH
                ? resolved
                : resolved.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
    }

    private static String cooldownMessage(int seconds) {
        return "Muitas tentativas. Aguarde " + seconds + "s.";
    }

    private static boolean isAsciiLetter(char character) {
        return character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z';
    }

    private static boolean isAsciiDigit(char character) {
        return character >= '0' && character <= '9';
    }
}

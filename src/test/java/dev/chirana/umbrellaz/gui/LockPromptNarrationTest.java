package dev.chirana.umbrellaz.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LockPromptNarrationTest {
    @Test
    void narratesTitleFocusedFieldValidationAndActions() {
        LockPromptModel model = LockPromptModel.create(LockPromptMode.CONFIRM_PASSWORD).insert("A12");

        LockPromptNarration narration = LockPromptNarration.from(model, false);

        assertTrue(narration.title().contains("Cadeado"));
        assertTrue(narration.focus().contains("Campo de senha"));
        assertTrue(narration.focus().contains("3 de 4"));
        assertTrue(narration.status().contains("formato inválido"));
        assertTrue(narration.actions().contains("Tab muda o foco"));
        assertTrue(narration.actions().contains("Escape cancela"));
    }

    @Test
    void narratesFocusedControlsAndSubmissionState() {
        LockPromptModel ready = LockPromptModel.create(LockPromptMode.CREATE_PASSWORD)
                .insert("A123")
                .tab(false);
        LockPromptNarration submit = LockPromptNarration.from(ready, false);
        assertTrue(submit.focus().contains("Botão Criar cadeado"));
        assertTrue(submit.focus().contains("Disponível"));

        LockPromptNarration submitControl = LockPromptNarration.forControl(ready, LockPromptFocus.SUBMIT);
        assertTrue(submitControl.title().contains("Botão Criar cadeado"));
        assertTrue(submitControl.focus().contains("Controle focado"));
        assertTrue(submitControl.status().contains("formato válido"));
        assertTrue(submitControl.actions().contains("Enter"));

        LockPromptNarration submitting = LockPromptNarration.from(ready.submit(), false);
        assertTrue(submitting.focus().contains("Botão Cancelar"));
        assertTrue(submitting.status().contains("Pedido em envio"));
        assertTrue(submitting.actions().contains("Escape"));
    }

    @Test
    void narratesErrorCooldownAndMinimumFallback() {
        LockPromptNarration error = LockPromptNarration.from(
                LockPromptModel.create(LockPromptMode.CONFIRM_PASSWORD).showError("Senha incorreta."), false);
        assertTrue(error.status().contains("Erro"));
        assertTrue(error.status().contains("Senha incorreta"));

        LockPromptNarration cooldown = LockPromptNarration.from(
                LockPromptModel.create(LockPromptMode.CONFIRM_PASSWORD).startCooldown(30), false);
        assertTrue(cooldown.status().contains("Tempo de espera"));
        assertTrue(cooldown.status().contains("30s"));

        LockPromptNarration minimum = LockPromptNarration.from(
                LockPromptModel.create(LockPromptMode.CREATE_PASSWORD), true);
        assertTrue(minimum.focus().contains("janela está pequena"));
        assertTrue(minimum.actions().contains("Aumente a janela"));
        assertTrue(minimum.actions().contains("Escape"));
    }
}

package dev.chirana.umbrellaz.gui;

import java.util.Objects;

public record LockPromptNarration(String title, String focus, String status, String actions) {
    public LockPromptNarration {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(focus, "focus");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(actions, "actions");
    }

    public static LockPromptNarration from(LockPromptModel model, boolean belowMinimum) {
        Objects.requireNonNull(model, "model");
        String title = model.mode().title() + ". " + model.mode().eyebrow() + ". "
                + model.mode().description();
        if (belowMinimum) {
            return new LockPromptNarration(
                    title,
                    "Conteúdo indisponível: a janela está pequena demais.",
                    status(model),
                    "Aumente a janela para continuar. Pressione Escape para cancelar.");
        }
        return new LockPromptNarration(title, focus(model), status(model), actions(model));
    }

    public static LockPromptNarration forControl(LockPromptModel model, LockPromptFocus control) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(control, "control");
        return new LockPromptNarration(
                controlTitle(model, control),
                "Controle focado: " + controlTitle(model, control) + ".",
                status(model),
                actions(model, control));
    }

    private static String focus(LockPromptModel model) {
        return switch (model.focus()) {
            case INPUT -> "Campo de senha. " + model.password().length()
                    + " de 4 caracteres preenchidos. Senha oculta. "
                    + (model.isPolicyValid() ? "Formato válido." : "Formato ainda inválido.");
            case SUBMIT -> "Botão " + model.mode().submitLabel() + ". "
                    + (model.canSubmit() ? "Disponível." : "Indisponível.");
            case CANCEL -> "Botão Cancelar. Disponível.";
        };
    }

    private static String controlTitle(LockPromptModel model, LockPromptFocus control) {
        return switch (control) {
            case INPUT -> "Campo de senha, " + model.password().length() + " de 4 caracteres, conteúdo oculto";
            case SUBMIT -> "Botão " + model.mode().submitLabel();
            case CANCEL -> "Botão Cancelar";
        };
    }

    private static String status(LockPromptModel model) {
        String state = switch (model.status()) {
            case EDITING -> model.isPolicyValid()
                    ? "Senha pronta para confirmação."
                    : "Editando senha. Use uma letra ASCII primeiro e depois três dígitos.";
            case SUBMITTING -> "Pedido em envio. Aguarde.";
            case ERROR -> "Erro. " + model.message();
            case COOLDOWN -> "Tempo de espera. " + model.message();
            case CLOSED -> "Tela fechada.";
        };
        if (model.status() == LockPromptStatus.CLOSED) {
            return state;
        }
        return state + " Campo de senha com " + model.password().length()
                + " de 4 caracteres, conteúdo oculto, formato "
                + (model.isPolicyValid() ? "válido." : "inválido.");
    }

    private static String actions(LockPromptModel model) {
        if (!model.canEdit()) {
            return "Pressione Escape para cancelar.";
        }
        return actions(model, model.focus());
    }

    private static String actions(LockPromptModel model, LockPromptFocus control) {
        if (!model.canEdit()) {
            return "Pressione Escape para cancelar.";
        }
        return switch (control) {
            case INPUT -> "Digite a senha. Tab muda o foco. Enter tenta confirmar. Escape cancela.";
            case SUBMIT -> "Pressione Enter para confirmar. Tab muda o foco. Escape cancela.";
            case CANCEL -> "Pressione Enter para cancelar. Tab muda o foco. Escape cancela.";
        };
    }
}

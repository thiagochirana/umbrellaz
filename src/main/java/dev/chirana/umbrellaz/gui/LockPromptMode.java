package dev.chirana.umbrellaz.gui;

public enum LockPromptMode {
    CREATE_PASSWORD(
            "NOVO CADEADO",
            "Crie uma senha para proteger este armazenamento.",
            "Criar cadeado"),
    CONFIRM_PASSWORD(
            "ACESSO PROTEGIDO",
            "Digite a senha para continuar.",
            "Confirmar");

    private final String eyebrow;
    private final String description;
    private final String submitLabel;

    LockPromptMode(String eyebrow, String description, String submitLabel) {
        this.eyebrow = eyebrow;
        this.description = description;
        this.submitLabel = submitLabel;
    }

    public String eyebrow() {
        return eyebrow;
    }

    public String title() {
        return "Cadeado Umbrellaz";
    }

    public String description() {
        return description;
    }

    public String submitLabel() {
        return submitLabel;
    }

    public String passwordHint() {
        return "Use 1 letra (A–Z) primeiro, seguida de 3 números.";
    }
}

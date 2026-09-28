package dev.chirana.umbrellaz.lock;

public final class PasswordCreationBusyException extends IllegalStateException {
    public PasswordCreationBusyException(Throwable cause) {
        super("Password creation executor is saturated or closed", cause);
    }
}

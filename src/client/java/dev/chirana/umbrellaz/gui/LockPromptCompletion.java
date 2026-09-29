package dev.chirana.umbrellaz.gui;

public interface LockPromptCompletion {
    void error(String message);

    void cooldown(int seconds);

    void close();
}

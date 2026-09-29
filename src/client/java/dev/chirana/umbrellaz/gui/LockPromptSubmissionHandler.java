package dev.chirana.umbrellaz.gui;

@FunctionalInterface
public interface LockPromptSubmissionHandler {
    void submit(LockPromptMode mode, String password, LockPromptCompletion completion);
}

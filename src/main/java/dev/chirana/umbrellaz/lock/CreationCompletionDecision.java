package dev.chirana.umbrellaz.lock;

final class CreationCompletionDecision {
    private CreationCompletionDecision() {
    }

    static Outcome decide(boolean persistenceSucceeded, boolean completionValid) {
        return persistenceSucceeded && completionValid ? Outcome.COMMIT : Outcome.COMPENSATE;
    }

    enum Outcome {
        COMMIT,
        COMPENSATE
    }
}

package com.aimastery.gateway.eval;

/** Suivi d'une execution en cours, pour l'afficher et pouvoir l'annuler. */
public interface EvalProgress {

    EvalProgress NONE = new EvalProgress() {
    };

    default void started(int totalCases) {
    }

    default void caseDone(String caseId, int doneCases) {
    }

    /** Lu entre deux cas : un appel de modele deja parti va jusqu'au bout. */
    default boolean cancelled() {
        return false;
    }
}

package com.aimastery.gateway.core;

import java.util.List;

/**
 * Levee quand toute la chaine de fallback a echoue.
 */
public class AllProvidersFailedException extends RuntimeException {

    private final List<String> attempted;

    public AllProvidersFailedException(List<String> attempted, Throwable lastCause) {
        super("Aucun fournisseur n'a repondu. Essayes dans l'ordre : " + attempted, lastCause);
        this.attempted = attempted;
    }

    public List<String> attempted() {
        return attempted;
    }
}

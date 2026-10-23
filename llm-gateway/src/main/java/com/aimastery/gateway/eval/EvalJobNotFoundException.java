package com.aimastery.gateway.eval;

public class EvalJobNotFoundException extends RuntimeException {

    public EvalJobNotFoundException(String id) {
        super("Execution inconnue : '" + id + "'");
    }
}

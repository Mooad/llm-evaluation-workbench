package com.aimastery.gateway.eval;

/**
 * Une seule evaluation a la fois (409) : deux executions paralleles se disputeraient le
 * modele local et fausseraient les latences mesurees des deux.
 */
public class EvalAlreadyRunningException extends RuntimeException {

    public EvalAlreadyRunningException(String runningId) {
        super("Une evaluation est deja en cours (" + runningId + ") : attends sa fin ou annule-la.");
    }
}

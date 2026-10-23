package com.aimastery.gateway.eval;

public enum Verdict {
    /** Le critere est respecte. */
    PASS,
    /** Le critere n'est pas respecte. */
    FAIL,
    /** Non evalue : dimension non demandee par le dataset, ou juge desactive. */
    SKIPPED,
    /** Impossible de conclure : appel en echec, juge illisible. Ne compte ni pour ni contre. */
    ERROR
}

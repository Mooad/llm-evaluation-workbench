package com.aimastery.gateway.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * @param method "regle" (verification deterministe) ou "juge:<fournisseur>/<modele>".
 *               Toujours affiche : un verdict de juge n'a pas la meme fiabilite qu'une regle.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DimensionScore(Dimension dimension, Verdict verdict, String method, String reason) {

    public static DimensionScore pass(Dimension d, String method, String reason) {
        return new DimensionScore(d, Verdict.PASS, method, reason);
    }

    public static DimensionScore fail(Dimension d, String method, String reason) {
        return new DimensionScore(d, Verdict.FAIL, method, reason);
    }

    public static DimensionScore skipped(Dimension d, String reason) {
        return new DimensionScore(d, Verdict.SKIPPED, "-", reason);
    }

    public static DimensionScore error(Dimension d, String method, String reason) {
        return new DimensionScore(d, Verdict.ERROR, method, reason);
    }
}

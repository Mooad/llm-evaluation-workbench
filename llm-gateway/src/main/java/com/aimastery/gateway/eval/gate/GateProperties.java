package com.aimastery.gateway.eval.gate;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parametres du test de non-regression (scripts/eval-gate.sh).
 *
 * @param useJudge        faux par defaut : des regles seules, c'est gratuit et reproductible ;
 *                        un juge LLM ajoute son propre bruit a la mesure
 * @param thresholdPoints null = gateway.eval.regression-threshold-points
 * @param promote         remplace la reference par cette execution si elle ne regresse pas
 */
@ConfigurationProperties(prefix = "gateway.eval.gate")
public record GateProperties(boolean enabled,
                             String dataset,
                             String provider,
                             String model,
                             boolean useJudge,
                             String judgeProvider,
                             String judgeModel,
                             Double thresholdPoints,
                             boolean promote) {

    public GateProperties {
        if (dataset == null || dataset.isBlank()) {
            dataset = "golden";
        }
    }
}

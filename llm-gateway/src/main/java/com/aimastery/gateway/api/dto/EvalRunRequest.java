package com.aimastery.gateway.api.dto;

import com.aimastery.gateway.eval.EvalRun;
import java.util.List;

/**
 * Tous les champs sont optionnels. Par defaut : golden dataset, tous les cas, juge actif,
 * temperature 0 (reproductibilite), 512 tokens, fallback interdit.
 *
 * @param datasetId   un dataset du catalogue (GET /eval/datasets) ; prime sur datasetPath
 * @param datasetPath emplacement brut (classpath:... ou file:...), pour un dataset hors catalogue
 * @param systemPrompt consigne envoyee en tete de chaque cas ; absente = aucune
 */
public record EvalRunRequest(String provider,
                             String model,
                             String datasetId,
                             String datasetPath,
                             List<String> caseIds,
                             Boolean useJudge,
                             String judgeProvider,
                             String judgeModel,
                             Double temperature,
                             Integer maxTokens,
                             Boolean allowFallback,
                             String systemPrompt) {

    public static EvalRunRequest defaults() {
        return new EvalRunRequest(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public EvalRun toRun(String datasetLocation) {
        return new EvalRun(provider, model, datasetLocation, caseIds,
                useJudge == null || useJudge,
                judgeProvider, judgeModel,
                temperature == null ? 0.0 : temperature,
                maxTokens == null ? 512 : maxTokens,
                allowFallback != null && allowFallback,
                systemPrompt);
    }
}

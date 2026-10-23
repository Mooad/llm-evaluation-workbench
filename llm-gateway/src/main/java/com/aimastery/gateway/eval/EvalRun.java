package com.aimastery.gateway.eval;

import java.util.List;

/**
 * Parametres d'une execution.
 *
 * @param allowFallback false par defaut : si le candidat tombe et qu'un autre fournisseur
 *                      repond a sa place, le cas est EXCLU. Sinon on mesurerait un melange
 *                      de fournisseurs en croyant en mesurer un seul.
 * @param systemPrompt  consigne envoyee en tete de chaque cas (role system), null = aucune.
 *                      C'est le levier mesure quand on compare deux executions.
 */
public record EvalRun(String provider,
                      String model,
                      String datasetPath,
                      List<String> caseIds,
                      boolean useJudge,
                      String judgeProvider,
                      String judgeModel,
                      double temperature,
                      int maxTokens,
                      boolean allowFallback,
                      String systemPrompt) {

    /** Sans prompt systeme. */
    public EvalRun(String provider, String model, String datasetPath, List<String> caseIds, boolean useJudge,
                   String judgeProvider, String judgeModel, double temperature, int maxTokens,
                   boolean allowFallback) {
        this(provider, model, datasetPath, caseIds, useJudge, judgeProvider, judgeModel, temperature,
                maxTokens, allowFallback, null);
    }
}

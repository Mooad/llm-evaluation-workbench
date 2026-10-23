package com.aimastery.gateway.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Rapport d'une execution. Ecrit sur disque a chaque run : c'est la comparaison de deux
 * rapports (avant / apres un changement de prompt, de modele, de fournisseur) qui a de
 * la valeur, jamais un rapport isole.
 *
 * Les champs ajoutes au fil du temps (datasetHash, settings, costPerCorrect...) sont null
 * dans les anciens rapports : la lecture les tolere, la comparaison le signale.
 *
 * @param datasetHash    empreinte des cas evalues (CaseFingerprint), pour savoir si deux
 *                       rapports mesurent la meme chose
 * @param costPerCorrect cout total / reponses CORRECTNESS PASS : le cout d'une bonne reponse,
 *                       seule grandeur juste pour comparer deux fournisseurs
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalReport(String runId,
                         String startedAt,
                         long durationMs,
                         String candidate,
                         String judge,
                         String dataset,
                         String datasetHash,
                         RunSettings settings,
                         int caseCount,
                         Map<Dimension, DimensionStats> dimensions,
                         Map<String, SchemaStats> schemaConformity,
                         LatencyStats latency,
                         int inputTokens,
                         int outputTokens,
                         Double totalCost,
                         Double costPerCorrect,
                         String currency,
                         List<String> warnings,
                         List<CaseResult> cases,
                         String reportFile) {

    /** Ce qui conditionne la mesure : deux rapports aux reglages differents se comparent mal. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunSettings(double temperature, int maxTokens, boolean useJudge, boolean allowFallback,
                              boolean filtered, String systemPrompt) {
    }

    /**
     * @param passRate passed / (passed + failed). Les SKIPPED et ERROR n'y entrent pas :
     *                 un cas qu'on n'a pas pu juger n'est ni une reussite ni un echec.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DimensionStats(int passed, int failed, int skipped, int errors, Double passRate) {
    }

    /**
     * Cas a schema mesures, par fournisseur qui a repondu. Les cas exclus (repli) ou sans
     * reponse n'y entrent pas : ils ne disent rien de la capacite du fournisseur.
     *
     * @param rate conform / cases
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SchemaStats(int cases, int conform, Double rate) {
    }

    /** Latence du candidat seul (les appels au juge ne sont pas comptes). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LatencyStats(Long avgMs, Long p50Ms, Long maxMs) {
    }
}

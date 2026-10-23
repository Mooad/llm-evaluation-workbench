package com.aimastery.gateway.eval;

import java.util.Map;

/**
 * Ce qu'il faut pour lister et choisir un rapport, sans charger ses cas.
 *
 * @param schemaRate cas a schema conformes / cas a schema mesures, tous fournisseurs confondus
 */
public record ReportSummary(String id,
                            String runId,
                            String startedAt,
                            long durationMs,
                            String candidate,
                            String judge,
                            String datasetId,
                            String datasetHash,
                            boolean filtered,
                            int caseCount,
                            Map<Dimension, EvalReport.DimensionStats> dimensions,
                            EvalReport.LatencyStats latency,
                            Double totalCost,
                            Double costPerCorrect,
                            String currency,
                            Double schemaRate,
                            int warningCount) {
}

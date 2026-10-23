package com.aimastery.gateway.api.dto;

import com.aimastery.gateway.core.AppliedOptions;
import com.aimastery.gateway.core.FinishReason;

/**
 * @param inputTokens   compte avec le tokenizer DU fournisseur : a suivre dans le temps
 *                      pour un fournisseur, jamais a comparer d'un fournisseur a l'autre
 * @param estimatedCost null si aucun tarif n'est configure pour ce modele
 * @param applied       reglages reellement envoyes au fournisseur, avec leur origine
 */
public record ChatResponse(String content,
                           String provider,
                           String requestedProvider,
                           boolean fallbackUsed,
                           String model,
                           Integer inputTokens,
                           Integer outputTokens,
                           Double estimatedCost,
                           String currency,
                           long latencyMs,
                           FinishReason finishReason,
                           String providerFinishReason,
                           AppliedOptions applied) {
}

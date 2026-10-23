package com.aimastery.gateway.api.dto;

import com.aimastery.gateway.core.AppliedOptions;
import com.aimastery.gateway.core.ChatService;
import com.aimastery.gateway.core.FinishReason;

/**
 * Dernier evenement d'un flux /chat/stream : tout ce qui se mesure une fois la
 * generation terminee. Le texte, lui, est deja parti fragment par fragment.
 *
 * @param firstTokenMs de la reception de la requete par le Gateway au premier fragment
 *                     envoye — fallback compris. C'est la latence que l'utilisateur ressent.
 * @param latencyMs    duree de l'appel au fournisseur qui a repondu, du debut a la fin du flux
 * @param costBasis    comment le cout a ete calcule, ou pourquoi il est inconnu
 * @param applied      reglages reellement envoyes au fournisseur, avec leur origine
 */
public record StreamSummary(String provider,
                            String requestedProvider,
                            boolean fallbackUsed,
                            String model,
                            Integer inputTokens,
                            Integer outputTokens,
                            Double estimatedCost,
                            String currency,
                            String costBasis,
                            Long firstTokenMs,
                            long latencyMs,
                            FinishReason finishReason,
                            String providerFinishReason,
                            AppliedOptions applied) {

    public static StreamSummary of(ChatService.ChatOutcome outcome, Long firstTokenMs) {
        return new StreamSummary(
                outcome.provider(),
                outcome.requestedProvider(),
                outcome.fallbackUsed(),
                outcome.result().model(),
                outcome.result().usage().inputTokens(),
                outcome.result().usage().outputTokens(),
                outcome.cost().amount(),
                outcome.cost().currency(),
                outcome.cost().basis(),
                firstTokenMs,
                outcome.latencyMs(),
                outcome.result().finishReason(),
                outcome.result().providerFinishReason(),
                outcome.applied());
    }
}

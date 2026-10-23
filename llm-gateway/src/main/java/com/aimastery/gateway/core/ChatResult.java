package com.aimastery.gateway.core;

/**
 * @param finishReason         vocabulaire du Gateway, identique chez tous les fournisseurs
 * @param providerFinishReason valeur brute du fournisseur, conservee pour le diagnostic
 */
public record ChatResult(String content,
                         String model,
                         Usage usage,
                         FinishReason finishReason,
                         String providerFinishReason) {

    /** Construit le resultat en normalisant le libelle du fournisseur. */
    public static ChatResult of(String content, String model, Usage usage, String rawFinishReason) {
        return new ChatResult(content, model, usage,
                FinishReason.fromProvider(rawFinishReason), rawFinishReason);
    }
}

package com.aimastery.gateway.core;

/**
 * Reglages de decodage. Tous nullables : null signifie "laisse le defaut du fournisseur".
 *
 * @param reasoning mode raisonnement (DeepSeek "thinking"). Attention : chez DeepSeek, les
 *                  tokens de raisonnement sont pris sur maxTokens — un petit budget peut
 *                  donner une reponse vide. Ignore par les fournisseurs sans ce mode.
 */
public record ChatOptions(Double temperature, Double topP, Integer maxTokens, Boolean reasoning) {

    public static ChatOptions defaults() {
        return new ChatOptions(null, null, null, null);
    }
}

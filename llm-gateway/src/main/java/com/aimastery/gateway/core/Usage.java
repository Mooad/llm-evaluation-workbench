package com.aimastery.gateway.core;

/**
 * Comptage des tokens. Sans ca, la latence seule n'est pas interpretable.
 */
public record Usage(Integer inputTokens, Integer outputTokens) {

    public static Usage unknown() {
        return new Usage(null, null);
    }

    public Integer totalTokens() {
        if (inputTokens == null || outputTokens == null) {
            return null;
        }
        return inputTokens + outputTokens;
    }
}

package com.aimastery.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxAttempts      essais par fournisseur, retry compris (1 = aucun retry)
 * @param backoffMs        attente avant le 2e essai, doublee a chaque essai suivant
 * @param failureThreshold pannes passageres consecutives avant d'ouvrir le circuit
 * @param openSeconds      duree pendant laquelle un circuit ouvert refuse les appels
 */
@ConfigurationProperties(prefix = "gateway.resilience")
public record ResilienceProperties(Integer maxAttempts, Integer backoffMs, Integer failureThreshold,
                                   Integer openSeconds) {

    public ResilienceProperties {
        maxAttempts = maxAttempts == null ? 2 : Math.max(1, maxAttempts);
        backoffMs = backoffMs == null ? 300 : Math.max(0, backoffMs);
        failureThreshold = failureThreshold == null ? 3 : Math.max(1, failureThreshold);
        openSeconds = openSeconds == null ? 30 : Math.max(1, openSeconds);
    }

    /** Ni retry ni circuit : pour les tests qui veulent la bascule immediate. */
    public static ResilienceProperties disabled() {
        return new ResilienceProperties(1, 0, Integer.MAX_VALUE, 1);
    }
}

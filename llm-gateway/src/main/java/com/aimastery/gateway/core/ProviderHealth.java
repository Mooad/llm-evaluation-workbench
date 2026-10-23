package com.aimastery.gateway.core;

/**
 * Etat d'un fournisseur, vu par une sonde legere (pas un appel de generation).
 *
 * @param modelAvailable le modele par defaut est-il reellement servi ? Un fournisseur
 *                       joignable dont le modele n'est pas installe echouera quand meme :
 *                       UP ne suffit pas, il faut aussi le modele.
 */
public record ProviderHealth(String provider,
                             Status status,
                             String defaultModel,
                             Boolean modelAvailable,
                             Long latencyMs,
                             String detail) {

    public enum Status {
        /** Joignable, et le modele par defaut est disponible. */
        UP,
        /** Joignable, mais quelque chose cloche (modele absent, cle manquante). */
        DEGRADED,
        /** Injoignable. */
        DOWN,
        /** Aucune sonde pour ce fournisseur. */
        UNKNOWN
    }

    public static ProviderHealth up(String provider, String model, boolean modelAvailable,
                                    long latencyMs, String detail) {
        return new ProviderHealth(provider, modelAvailable ? Status.UP : Status.DEGRADED,
                model, modelAvailable, latencyMs, detail);
    }

    public static ProviderHealth down(String provider, String model, Long latencyMs, String detail) {
        return new ProviderHealth(provider, Status.DOWN, model, null, latencyMs, detail);
    }

    public static ProviderHealth degraded(String provider, String model, String detail) {
        return new ProviderHealth(provider, Status.DEGRADED, model, null, null, detail);
    }

    public static ProviderHealth unknown(String provider, String model, String detail) {
        return new ProviderHealth(provider, Status.UNKNOWN, model, null, null, detail);
    }
}

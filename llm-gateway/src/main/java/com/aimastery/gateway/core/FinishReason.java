package com.aimastery.gateway.core;

/**
 * Pourquoi la generation s'est arretee, en vocabulaire du Gateway.
 *
 * Chaque fournisseur a le sien : les APIs compatibles OpenAI (DeepSeek) disent "length"
 * la ou d'autres disent "max_tokens", "tool_calls" la ou d'autres disent "tool_use".
 * Sans normalisation, l'appelant devrait connaitre le fournisseur pour
 * interpreter la reponse — et l'abstraction ne servirait plus a rien.
 *
 * La valeur brute reste disponible dans ChatResult.providerFinishReason,
 * parce qu'on ne jette jamais une information de diagnostic.
 */
public enum FinishReason {

    /** Le modele a termine de lui-meme. */
    COMPLETE,

    /** Coupe par la limite de tokens : la reponse est incomplete. */
    TRUNCATED,

    /** Refus du modele pour raison de securite. */
    REFUSED,

    /** Le modele demande l'execution d'un outil. */
    TOOL_USE,

    /** Non renseigne par le fournisseur, ou valeur non reconnue. */
    UNKNOWN;

    /**
     * Traduit la valeur brute d'un fournisseur.
     * Les libelles couvrent Ollama, les APIs compatibles OpenAI et Anthropic, pour
     * qu'un nouvel adaptateur n'ait pas a toucher a cette table.
     */
    public static FinishReason fromProvider(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        return switch (raw.toLowerCase()) {
            case "stop", "end_turn", "stop_sequence" -> COMPLETE;
            case "length", "max_tokens" -> TRUNCATED;
            case "refusal", "content_filter" -> REFUSED;
            case "tool_use", "tool_calls", "function_call" -> TOOL_USE;
            default -> UNKNOWN;
        };
    }

    /** Vrai quand la reponse ne doit pas etre consideree comme complete. */
    public boolean isIncomplete() {
        return this == TRUNCATED || this == REFUSED;
    }
}

package com.aimastery.gateway.structured;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Resultat d'un appel a sortie structuree.
 *
 * On renvoie TOUJOURS la sortie brute, meme en cas d'echec : sans elle,
 * impossible de comprendre pourquoi le modele n'a pas respecte le schema.
 */
public record StructuredOutcome(boolean valid,
                                JsonNode data,
                                String raw,
                                List<String> violations) {

    public static StructuredOutcome ok(JsonNode data, String raw) {
        return new StructuredOutcome(true, data, raw, List.of());
    }

    public static StructuredOutcome invalid(String raw, List<String> violations) {
        return new StructuredOutcome(false, null, raw, violations);
    }
}

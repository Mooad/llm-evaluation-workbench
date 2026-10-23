package com.aimastery.gateway.core;

/**
 * Reglages globaux d'un fournisseur, appliques quand la requete ne les precise pas.
 * Tous nullables : null = ne rien imposer, le fournisseur garde son propre defaut.
 */
public record GenerationDefaults(Boolean reasoning, Integer maxTokens, Double temperature) {

    public static final GenerationDefaults NONE = new GenerationDefaults(null, null, null);
}

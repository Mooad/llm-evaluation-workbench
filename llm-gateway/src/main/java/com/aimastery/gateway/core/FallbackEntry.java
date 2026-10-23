package com.aimastery.gateway.core;

/**
 * Une etape de la chaine de fallback.
 *
 * @param model null = le modele par defaut du fournisseur
 */
public record FallbackEntry(String provider, String model) {
}

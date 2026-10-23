package com.aimastery.gateway.core;

/**
 * Ou ChatService lit les defauts globaux. Le domaine ignore d'ou ils viennent :
 * l'implementation (un fichier) vit dans config/.
 */
@FunctionalInterface
public interface GenerationDefaultsSource {

    GenerationDefaults forProvider(String provider);

    static GenerationDefaultsSource none() {
        return provider -> GenerationDefaults.NONE;
    }
}

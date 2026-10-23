package com.aimastery.gateway.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Toute la configuration fournisseur vit ici, jamais dans le code.
 * Changer de modele ou de fournisseur = editer application.yml, rien d'autre.
 *
 * @param fallbackOrder ordre des fournisseurs, donc ordre de repli. Explicite parce que
 *                      l'ordre de la map providers n'est pas fiable : application-secrets.yml,
 *                      fusionne en premier, y place ses cles devant.
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(String defaultProvider, Map<String, ProviderConfig> providers,
                                List<String> fallbackOrder) {

    @ConstructorBinding
    public GatewayProperties {
        fallbackOrder = fallbackOrder == null ? List.of() : List.copyOf(fallbackOrder);
    }

    /** Sans ordre explicite : celui de la map providers. */
    public GatewayProperties(String defaultProvider, Map<String, ProviderConfig> providers) {
        this(defaultProvider, providers, null);
    }

    public ProviderConfig provider(String name) {
        ProviderConfig config = providers.get(name);
        if (config == null) {
            throw new IllegalStateException(
                    "Aucune configuration sous gateway.providers." + name);
        }
        return config;
    }

    public record ProviderConfig(String baseUrl,
                                 String defaultModel,
                                 String apiKey,
                                 int timeoutSeconds) {
    }
}

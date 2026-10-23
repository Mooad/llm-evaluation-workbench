package com.aimastery.gateway.core;

import com.aimastery.gateway.config.GatewayProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Construit la chaine de fournisseurs a essayer, dans l'ordre.
 *
 * Le primaire est celui demande (ou celui par defaut), avec le modele demande.
 * Chaque repli prend son propre modele par defaut : un nom de modele n'a de sens
 * que chez le fournisseur qui le sert.
 *
 * Aucun nom de fournisseur ici : la chaine se deduit de ce qui est charge, dans l'ordre
 * de gateway.fallback-order (voir ProviderRegistry).
 */
@Component
public class FallbackManager {

    private final ProviderRegistry registry;
    private final GatewayProperties properties;

    public FallbackManager(ProviderRegistry registry, GatewayProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public List<FallbackEntry> buildChain(String requestedProvider, String requestedModel) {
        boolean explicit = requestedProvider != null && !requestedProvider.isBlank();
        FallbackEntry primary = explicit
                ? new FallbackEntry(requestedProvider, requestedModel)
                : new FallbackEntry(properties.defaultProvider(), null);

        List<FallbackEntry> chain = new ArrayList<>();
        chain.add(primary);
        for (String provider : registry.available()) {
            if (!provider.equals(primary.provider())) {
                chain.add(new FallbackEntry(provider, null));
            }
        }
        return chain;
    }
}

package com.aimastery.gateway.core;

import com.aimastery.gateway.config.GatewayProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Resout un nom de fournisseur vers son implementation. Spring injecte toutes les
 * implementations de LlmProvider presentes dans le contexte : en ajouter une suffit.
 *
 * L'ordre est celui de gateway.fallback-order (a defaut, de gateway.providers) : c'est aussi
 * l'ordre de repli. L'ordre de decouverte des beans, lui, ne garantit rien.
 */
@Component
public class ProviderRegistry {

    private final Map<String, LlmProvider> providers = new LinkedHashMap<>();
    private final String defaultProvider;

    public ProviderRegistry(List<LlmProvider> discovered, GatewayProperties properties) {
        Map<String, LlmProvider> byName = new LinkedHashMap<>();
        discovered.forEach(provider -> byName.put(provider.name(), provider));
        List<String> order = !properties.fallbackOrder().isEmpty()
                ? properties.fallbackOrder()
                : properties.providers() == null ? List.of() : List.copyOf(properties.providers().keySet());
        order.stream()
                .filter(byName::containsKey)
                .forEach(name -> providers.put(name, byName.get(name)));
        byName.forEach(providers::putIfAbsent);
        this.defaultProvider = properties.defaultProvider();

        if (!providers.containsKey(defaultProvider)) {
            throw new IllegalStateException(
                    "gateway.default-provider = '" + defaultProvider
                            + "' mais aucun fournisseur de ce nom n'est charge. Disponibles : "
                            + providers.keySet());
        }
    }

    public LlmProvider resolve(String requested) {
        String name = (requested == null || requested.isBlank()) ? defaultProvider : requested;
        LlmProvider provider = providers.get(name);
        if (provider == null) {
            throw new ProviderNotFoundException(name, providers.keySet());
        }
        return provider;
    }

    public Set<String> available() {
        return providers.keySet();
    }

    public String defaultProvider() {
        return defaultProvider;
    }
}

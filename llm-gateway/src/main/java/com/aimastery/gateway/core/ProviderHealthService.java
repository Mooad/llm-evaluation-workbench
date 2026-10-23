package com.aimastery.gateway.core;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Interroge tous les fournisseurs EN PARALLELE, avec une limite de temps.
 *
 * En serie, un seul fournisseur injoignable ferait attendre tous les autres :
 * la page de statut deviendrait inutilisable au moment ou elle sert le plus.
 */
@Service
public class ProviderHealthService {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    private final ProviderRegistry registry;

    public ProviderHealthService(ProviderRegistry registry) {
        this.registry = registry;
    }

    public List<ProviderHealth> checkAll() {
        List<LlmProvider> providers = registry.available().stream().map(registry::resolve).toList();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, providers.size()));
        try {
            List<CompletableFuture<ProviderHealth>> futures = providers.stream()
                    .map(provider -> CompletableFuture
                            .supplyAsync(() -> safeCheck(provider), pool)
                            .completeOnTimeout(
                                    ProviderHealth.down(provider.name(), provider.defaultModel(),
                                            PROBE_TIMEOUT.toMillis(),
                                            "sonde sans reponse en " + PROBE_TIMEOUT.toSeconds() + " s"),
                                    PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            pool.shutdownNow();
        }
    }

    private ProviderHealth safeCheck(LlmProvider provider) {
        try {
            return provider.health();
        } catch (RuntimeException e) {
            // Une sonde ne doit jamais faire tomber la page de statut.
            return ProviderHealth.down(provider.name(), provider.defaultModel(), null,
                    e.getClass().getSimpleName() + " : " + e.getMessage());
        }
    }
}

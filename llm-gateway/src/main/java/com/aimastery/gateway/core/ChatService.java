package com.aimastery.gateway.core;

import com.aimastery.gateway.observability.CallLogger;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Le seul chemin d'appel du Gateway : resoudre, appeler, mesurer, journaliser.
 *
 * Avec un FallbackManager, on parcourt une chaine de fournisseurs jusqu'au premier
 * succes. Sans lui, on appelle directement le fournisseur demande.
 */
@Service
public class ChatService {

    private final ProviderRegistry registry;
    private final CallLogger callLogger;
    private final FallbackManager fallbackManager;
    private final CostEstimator costEstimator;
    private final GenerationDefaultsSource defaults;
    private final ProviderGuard guard;

    /** Utilise par Spring — l'annotation leve l'ambiguite entre les constructeurs. */
    @Autowired
    public ChatService(ProviderRegistry registry,
                       CallLogger callLogger,
                       @Nullable FallbackManager fallbackManager,
                       CostEstimator costEstimator,
                       GenerationDefaultsSource defaults,
                       ProviderGuard guard) {
        this.registry = registry;
        this.callLogger = callLogger;
        this.fallbackManager = fallbackManager;
        this.costEstimator = costEstimator;
        this.defaults = defaults;
        this.guard = guard;
    }

    /** Sans retry ni circuit breaker. */
    public ChatService(ProviderRegistry registry, CallLogger callLogger,
                       @Nullable FallbackManager fallbackManager, CostEstimator costEstimator,
                       GenerationDefaultsSource defaults) {
        this(registry, callLogger, fallbackManager, costEstimator, defaults, ProviderGuard.disabled());
    }

    /** Sans estimation de cout, defauts globaux, retry ni circuit breaker. */
    public ChatService(ProviderRegistry registry, CallLogger callLogger,
                       @Nullable FallbackManager fallbackManager) {
        this(registry, callLogger, fallbackManager, CostEstimator.disabled(), GenerationDefaultsSource.none());
    }

    /** Sans fallback — pratique pour les tests. */
    public ChatService(ProviderRegistry registry, CallLogger callLogger) {
        this(registry, callLogger, null);
    }

    public ChatOutcome chat(String requestedProvider,
                            String requestedModel,
                            List<Message> messages,
                            ChatOptions options) {
        return execute(requestedProvider, requestedModel, messages, options, LlmProvider::chat, () -> true);
    }

    /**
     * Comme chat, mais chaque fragment part dans onDelta des son arrivee.
     *
     * Le fallback ne joue que tant que rien n'a ete emis : une fois du texte envoye au
     * client, basculer collerait deux reponses differentes dans le meme flux.
     */
    public ChatOutcome stream(String requestedProvider,
                              String requestedModel,
                              List<Message> messages,
                              ChatOptions options,
                              Consumer<String> onDelta) {
        AtomicBoolean emitted = new AtomicBoolean();
        Consumer<String> tracked = delta -> {
            emitted.set(true);
            onDelta.accept(delta);
        };
        return execute(requestedProvider, requestedModel, messages, options,
                (provider, call) -> provider.stream(call, tracked), () -> !emitted.get());
    }

    private ChatOutcome execute(String requestedProvider,
                                String requestedModel,
                                List<Message> messages,
                                ChatOptions options,
                                Invocation invocation,
                                BooleanSupplier fallbackAllowed) {

        if (fallbackManager == null) {
            return executeSingle(requestedProvider, requestedModel, messages, options, invocation);
        }

        // Un fournisseur inconnu est une erreur du client, pas une panne :
        // on echoue tout de suite au lieu de basculer sur un autre fournisseur.
        if (requestedProvider != null && !requestedProvider.isBlank()) {
            registry.resolve(requestedProvider);
        }

        List<FallbackEntry> chain = fallbackManager.buildChain(requestedProvider, requestedModel);
        ProviderCallException lastFailure = null;

        for (int i = 0; i < chain.size(); i++) {
            FallbackEntry entry = chain.get(i);
            boolean isPrimary = (i == 0);

            LlmProvider provider = registry.resolve(entry.provider());
            String model = resolveModel(entry, requestedModel, isPrimary, provider);

            // Les defauts sont ceux du fournisseur appele : un repli recoit les siens.
            AppliedOptions applied = AppliedOptions.resolve(options, defaults.forProvider(provider.name()));
            long startNanos = System.nanoTime();
            try {
                ChatCall call = new ChatCall(model, messages, applied.toChatOptions());
                // Retry et circuit d'abord sur CE fournisseur ; le fallback ne vient qu'apres.
                ChatResult result = guard.call(provider.name(), () -> invocation.call(provider, call), fallbackAllowed);
                return success(provider, model, result, startNanos, !isPrimary, chain.get(0).provider(), applied);

            } catch (ProviderCallException e) {
                // Seule une panne du fournisseur declenche la bascule.
                callLogger.failure(provider.name(), model, elapsedMs(startNanos), e);
                if (!fallbackAllowed.getAsBoolean()) {
                    throw e;
                }
                lastFailure = e;
            } catch (RuntimeException e) {
                // Client parti en plein flux, bug... : pas de bascule, mais l'appel a pu
                // consommer des tokens, il doit apparaitre dans les logs de cout.
                callLogger.failure(provider.name(), model, elapsedMs(startNanos), e);
                throw e;
            }
        }

        throw new AllProvidersFailedException(
                chain.stream().map(FallbackEntry::provider).toList(), lastFailure);
    }

    private ChatOutcome executeSingle(String requestedProvider,
                                      String requestedModel,
                                      List<Message> messages,
                                      ChatOptions options,
                                      Invocation invocation) {

        LlmProvider provider = registry.resolve(requestedProvider);
        String model = (requestedModel == null || requestedModel.isBlank())
                ? provider.defaultModel()
                : requestedModel;

        AppliedOptions applied = AppliedOptions.resolve(options, defaults.forProvider(provider.name()));
        long startNanos = System.nanoTime();
        try {
            ChatCall call = new ChatCall(model, messages, applied.toChatOptions());
            ChatResult result = guard.call(provider.name(), () -> invocation.call(provider, call), () -> true);
            return success(provider, model, result, startNanos, false, provider.name(), applied);
        } catch (RuntimeException e) {
            callLogger.failure(provider.name(), model, elapsedMs(startNanos), e);
            throw e;
        }
    }

    private ChatOutcome success(LlmProvider provider, String model, ChatResult result,
                                long startNanos, boolean fallbackUsed, String requestedProvider,
                                AppliedOptions applied) {
        long latencyMs = elapsedMs(startNanos);
        CostEstimate cost = costEstimator.estimate(result.model(), result.usage());
        callLogger.success(provider.name(), model, result.usage(), latencyMs,
                result.finishReason(), result.providerFinishReason(), cost);
        return new ChatOutcome(provider.name(), result, latencyMs, fallbackUsed, requestedProvider, cost, applied);
    }

    private String resolveModel(FallbackEntry entry,
                                String requestedModel,
                                boolean isPrimary,
                                LlmProvider provider) {
        // Le modele demande ne vaut que pour le fournisseur primaire : "qwen3-coder:30b"
        // n'a aucun sens chez DeepSeek. Chaque repli utilise son propre modele par defaut.
        if (isPrimary && requestedModel != null && !requestedModel.isBlank()) {
            return requestedModel;
        }
        if (entry.model() != null && !entry.model().isBlank()) {
            return entry.model();
        }
        return provider.defaultModel();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @FunctionalInterface
    private interface Invocation {
        ChatResult call(LlmProvider provider, ChatCall call);
    }

    public record ChatOutcome(String provider,
                              ChatResult result,
                              long latencyMs,
                              boolean fallbackUsed,
                              String requestedProvider,
                              CostEstimate cost,
                              AppliedOptions applied) {
    }
}

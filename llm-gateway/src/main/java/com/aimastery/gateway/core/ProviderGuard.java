package com.aimastery.gateway.core;

import com.aimastery.gateway.config.ResilienceProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Retry et circuit breaker, par fournisseur, autour de chaque appel.
 *
 * Retry : une panne passagere (FailureClassifier) est reessayee apres une attente croissante,
 * sur le meme fournisseur, avant de passer au fallback.
 *
 * Circuit breaker : apres N pannes passageres consecutives, le fournisseur est ecarte pendant
 * un delai (OUVERT) — le fallback part sans attendre au lieu de payer un timeout a chaque
 * requete. Ensuite un seul appel d'essai passe (MI-OUVERT) : succes = referme, echec = rouvre.
 */
@Component
public class ProviderGuard {

    private static final Logger log = LoggerFactory.getLogger("gateway.resilience");

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** @param openUntil fin de l'ouverture, null quand le circuit est ferme */
    public record CircuitView(State state, int consecutiveFailures, Instant openUntil) {
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final ResilienceProperties properties;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<String, Circuit> circuits = new ConcurrentHashMap<>();

    @Autowired
    public ProviderGuard(ResilienceProperties properties) {
        this(properties, Clock.systemUTC(), Thread::sleep);
    }

    ProviderGuard(ResilienceProperties properties, Clock clock, Sleeper sleeper) {
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public static ProviderGuard disabled() {
        return new ProviderGuard(ResilienceProperties.disabled());
    }

    /**
     * @param retryAllowed lu avant chaque nouvel essai : faux des qu'un flux a commence,
     *                     car reessayer renverrait le debut de la reponse une seconde fois
     */
    public ChatResult call(String provider, Supplier<ChatResult> action, BooleanSupplier retryAllowed) {
        Circuit circuit = circuits.computeIfAbsent(provider, p -> new Circuit());
        for (int attempt = 1; ; attempt++) {
            circuit.acquire(provider, clock.instant());
            try {
                ChatResult result = action.get();
                circuit.onSuccess();
                return result;
            } catch (ProviderCallException e) {
                boolean transientFailure = FailureClassifier.isTransient(e);
                boolean opened = circuit.onFailure(transientFailure, clock.instant(), properties);
                if (opened) {
                    log.warn("provider={} circuit=OPEN duree_s={} cause={}", provider,
                            properties.openSeconds(), e.getMessage());
                }
                if (!transientFailure || opened || attempt >= properties.maxAttempts()
                        || !retryAllowed.getAsBoolean()) {
                    throw e;
                }
                long backoff = (long) properties.backoffMs() << (attempt - 1);
                log.info("provider={} retry={}/{} attente_ms={} cause={} , httpStatus={}", provider, attempt + 1,
                        properties.maxAttempts(), backoff, e.getMessage(), e.httpStatus());
                pause(backoff, e);
            }
        }
    }

    /** Etat de chaque circuit deja sollicite, pour /providers/status. */
    public Map<String, CircuitView> circuits() {
        Map<String, CircuitView> views = new TreeMap<>();
        Instant now = clock.instant();
        circuits.forEach((provider, circuit) -> views.put(provider, circuit.view(now)));
        return views;
    }

    private void pause(long millis, ProviderCallException failure) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure;
        }
    }

    /** Etat d'un circuit. Synchronise : plusieurs requetes touchent le meme fournisseur. */
    private static final class Circuit {
        private int consecutiveFailures;
        private Instant openUntil;
        private boolean trialInFlight;

        synchronized void acquire(String provider, Instant now) {
            if (openUntil == null) {
                return;
            }
            if (now.isBefore(openUntil) || trialInFlight) {
                // Rejet immediat : pas d'aller-retour reseau, le fallback enchaine tout de suite.
                throw new ProviderCallException(provider, "circuit ouvert apres "
                        + consecutiveFailures + " pannes consecutives, appel ecarte", null);
            }
            trialInFlight = true;
        }

        synchronized void onSuccess() {
            consecutiveFailures = 0;
            openUntil = null;
            trialInFlight = false;
        }

        /** @return vrai si cet echec vient d'ouvrir (ou de rouvrir) le circuit */
        synchronized boolean onFailure(boolean transientFailure, Instant now, ResilienceProperties properties) {
            if (!transientFailure) {
                // Le fournisseur a repondu (4xx...) : il est joignable, le circuit n'a pas a s'ouvrir.
                onSuccess();
                return false;
            }
            consecutiveFailures++;
            if (trialInFlight || consecutiveFailures >= properties.failureThreshold()) {
                trialInFlight = false;
                openUntil = now.plusSeconds(properties.openSeconds());
                return true;
            }
            return false;
        }

        synchronized CircuitView view(Instant now) {
            State state = openUntil == null ? State.CLOSED
                    : now.isBefore(openUntil) ? State.OPEN
                    : State.HALF_OPEN;
            return new CircuitView(state, consecutiveFailures, openUntil);
        }
    }
}

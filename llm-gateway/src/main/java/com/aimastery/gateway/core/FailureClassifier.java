package com.aimastery.gateway.core;

import java.net.SocketTimeoutException;
import java.util.Set;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Passagere ou definitive ? Seule une panne passagere merite un nouvel essai et compte
 * pour le circuit breaker.
 *
 *   passagere  : 429, 500, 502, 503, 504, connexion refusee ou coupee
 *   definitive : 4xx (requete invalide, cle refusee, modele inconnu), reponse illisible,
 *                cle absente — reessayer donnerait le meme resultat
 *   timeout de lecture : definitif ici. Reessayer un appel qui a deja pris 180 s doublerait
 *                        l'attente ; le fallback est plus utile que l'insistance.
 */
public final class FailureClassifier {

    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(429, 500, 502, 503, 504,408);

    private FailureClassifier() {
    }

    public static boolean isTransient(ProviderCallException e) {
        if (e.httpStatus() != null) {
            return TRANSIENT_STATUSES.contains(e.httpStatus());
        }
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException response) {
                return TRANSIENT_STATUSES.contains(response.getStatusCode().value());
            }
            if (cause instanceof ResourceAccessException access) {
                return !(rootCause(access) instanceof SocketTimeoutException);
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}

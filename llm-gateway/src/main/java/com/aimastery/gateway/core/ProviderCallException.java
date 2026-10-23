package com.aimastery.gateway.core;

/**
 * Panne d'un fournisseur : declenche retry (si passagere), circuit breaker et fallback.
 *
 * @see FailureClassifier pour la distinction passagere / definitive
 */
public class ProviderCallException extends RuntimeException {

    private final String provider;
    private final Integer httpStatus;

    public ProviderCallException(String provider, String message, Throwable cause) {
        this(provider, message, cause, null);
    }

    /** @param httpStatus statut HTTP du fournisseur quand il n'est pas porte par la cause (flux) */
    public ProviderCallException(String provider, String message, Throwable cause, Integer httpStatus) {
        super(message, cause);
        this.provider = provider;
        this.httpStatus = httpStatus;
    }

    public String provider() {
        return provider;
    }

    public Integer httpStatus() {
        return httpStatus;
    }
}

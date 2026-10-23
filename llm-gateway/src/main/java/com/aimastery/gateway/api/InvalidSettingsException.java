package com.aimastery.gateway.api;

/** Reglage global refuse (ex. raisonnement sur un fournisseur qui n'en a pas) : 400. */
public class InvalidSettingsException extends RuntimeException {

    public InvalidSettingsException(String message) {
        super(message);
    }
}

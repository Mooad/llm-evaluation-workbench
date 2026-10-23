package com.aimastery.gateway.core;

import java.util.Set;

public class ProviderNotFoundException extends RuntimeException {

    public ProviderNotFoundException(String requested, Set<String> available) {
        super("Fournisseur inconnu : '" + requested + "'. Disponibles : " + available);
    }
}

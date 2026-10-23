package com.aimastery.gateway.eval;

public class ReportNotFoundException extends RuntimeException {

    public ReportNotFoundException(String id) {
        super("Rapport introuvable : '" + id + "'");
    }
}

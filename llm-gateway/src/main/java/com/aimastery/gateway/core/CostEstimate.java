package com.aimastery.gateway.core;

/**
 * @param amount   null quand le tarif du modele est inconnu — on ne devine jamais un prix
 * @param basis    comment le montant a ete obtenu, ou pourquoi il manque
 */
public record CostEstimate(Double amount, String currency, String basis) {

    public static CostEstimate of(double amount, String currency, String basis) {
        return new CostEstimate(amount, currency, basis);
    }

    public static CostEstimate unknown(String currency, String basis) {
        return new CostEstimate(null, currency, basis);
    }

    public boolean isKnown() {
        return amount != null;
    }
}

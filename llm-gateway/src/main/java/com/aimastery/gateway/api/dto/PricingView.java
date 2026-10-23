package com.aimastery.gateway.api.dto;

import java.util.List;

/**
 * La table de prix telle que le Gateway l'applique, pour qu'un cout affiche soit
 * verifiable a la main.
 *
 * @param source ou modifier ces tarifs
 */
public record PricingView(String currency, String source, List<ModelPrice> models) {

    /** Prix pour un million de tokens, comme les grilles des fournisseurs. */
    public record ModelPrice(String model, double inputPer1m, double outputPer1m) {
    }
}

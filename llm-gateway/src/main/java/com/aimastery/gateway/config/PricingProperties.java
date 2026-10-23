package com.aimastery.gateway.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tarifs par modele, en unites monetaires abstraites.
 *
 * Le Gateway ne connait aucun tarif en dur : un prix change, tu edites le yaml.
 * Un modele absent de la table n'est pas facture a zero — il est marque "inconnu",
 * ce qui est tres different (zero = local et gratuit ; inconnu = on ne sait pas).
 */
@ConfigurationProperties(prefix = "gateway.pricing")
public record PricingProperties(String currency, Map<String, ModelPrice> models) {

    public PricingProperties {
        if (currency == null || currency.isBlank()) {
            currency = "USD";
        }
        if (models == null) {
            models = Map.of();
        }
    }

    /** Prix pour un million de tokens, en entree et en sortie. */
    public record ModelPrice(double inputPer1m, double outputPer1m) {
    }
}

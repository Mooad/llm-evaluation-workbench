package com.aimastery.gateway.core;

import com.aimastery.gateway.config.PricingProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Traduit des tokens en cout.
 *
 * Rappel utile : les tokens de deux fournisseurs ne sont PAS comparables entre eux
 * (tokenizers differents). Le cout, si — c'est la seule grandeur qui permet de
 * comparer deux fournisseurs sur la meme tache.
 */
@Component
public class CostEstimator {

    private static final Pattern SNAPSHOT_DATE = Pattern.compile("-\\d{4}-\\d{2}-\\d{2}$");

    private final PricingProperties pricing;

    public CostEstimator(PricingProperties pricing) {
        this.pricing = pricing;
    }

    /** Estimateur sans table de prix : tout est inconnu. Pratique pour les tests. */
    public static CostEstimator disabled() {
        return new CostEstimator(new PricingProperties("USD", Map.of()));
    }

    public CostEstimate estimate(String model, Usage usage) {
        String currency = pricing.currency();

        if (usage == null || usage.inputTokens() == null || usage.outputTokens() == null) {
            return CostEstimate.unknown(currency, "tokens non rapportes par le fournisseur");
        }
        String priced = pricedModel(model);
        if (priced == null) {
            return CostEstimate.unknown(currency,
                    "aucun tarif configure pour '" + model + "' (gateway.pricing.models)");
        }
        PricingProperties.ModelPrice price = pricing.models().get(priced);

        BigDecimal amount = BigDecimal.valueOf(usage.inputTokens())
                .multiply(BigDecimal.valueOf(price.inputPer1m()))
                .add(BigDecimal.valueOf(usage.outputTokens())
                        .multiply(BigDecimal.valueOf(price.outputPer1m())))
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);

        String basis = amount.signum() == 0
                ? "modele local, aucun cout a l'appel"
                : usage.inputTokens() + " in x " + price.inputPer1m() + " + "
                        + usage.outputTokens() + " out x " + price.outputPer1m() + " / 1M"
                        + (priced.equals(model) ? "" : " (tarif " + priced + ")");

        return CostEstimate.of(amount.doubleValue(), currency, basis);
    }

    /**
     * OpenAI renvoie la version datee du modele (gpt-4.1-mini-2025-04-14) alors que les
     * grilles de prix sont par famille (gpt-4.1-mini) : sans ce repli, cout "inconnu".
     */
    private String pricedModel(String model) {
        if (model == null) {
            return null;
        }
        if (pricing.models().containsKey(model)) {
            return model;
        }
        String family = SNAPSHOT_DATE.matcher(model).replaceFirst("");
        return pricing.models().containsKey(family) ? family : null;
    }
}

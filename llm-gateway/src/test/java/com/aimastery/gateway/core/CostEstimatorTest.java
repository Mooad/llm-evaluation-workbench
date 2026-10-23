package com.aimastery.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.gateway.config.PricingProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CostEstimatorTest {

    private final CostEstimator estimator = new CostEstimator(new PricingProperties("USD", Map.of(
            "claude-opus-5", new PricingProperties.ModelPrice(5.0, 25.0),
            "qwen3-coder:30b", new PricingProperties.ModelPrice(0, 0))));

    @Test
    void calcule_le_cout_a_partir_des_tokens() {
        CostEstimate cost = estimator.estimate("claude-opus-5", new Usage(1_000_000, 1_000_000));

        assertThat(cost.amount()).isEqualTo(30.0);   // 5 en entree + 25 en sortie
        assertThat(cost.currency()).isEqualTo("USD");
    }

    @Test
    void un_modele_local_coute_zero_et_le_dit() {
        CostEstimate cost = estimator.estimate("qwen3-coder:30b", new Usage(500, 200));

        assertThat(cost.amount()).isZero();
        assertThat(cost.basis()).contains("local");
    }

    @Test
    void un_modele_sans_tarif_est_inconnu_jamais_gratuit() {
        CostEstimate cost = estimator.estimate("modele-mystere", new Usage(100, 100));

        assertThat(cost.isKnown()).isFalse();
        assertThat(cost.amount()).isNull();
        assertThat(cost.basis()).contains("aucun tarif configure");
    }

    @Test
    void sans_tokens_rapportes_le_cout_est_inconnu() {
        assertThat(estimator.estimate("claude-opus-5", Usage.unknown()).isKnown()).isFalse();
    }

    @Test
    void l_estimateur_desactive_ne_facture_rien() {
        assertThat(CostEstimator.disabled().estimate("claude-opus-5", new Usage(10, 10)).isKnown()).isFalse();
    }

    @Test
    void une_version_datee_prend_le_tarif_de_sa_famille() {
        CostEstimator openai = new CostEstimator(new PricingProperties("USD", Map.of(
                "gpt-4.1-mini", new PricingProperties.ModelPrice(0.40, 1.60))));

        CostEstimate cost = openai.estimate("gpt-4.1-mini-2025-04-14", new Usage(1_000_000, 0));

        assertThat(cost.amount()).isEqualTo(0.40);
        assertThat(cost.basis()).contains("tarif gpt-4.1-mini");
    }
}

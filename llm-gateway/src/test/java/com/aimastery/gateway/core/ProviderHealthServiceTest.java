package com.aimastery.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.gateway.config.GatewayProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderHealthServiceTest {

    private ProviderHealthService service(LlmProvider... providers) {
        Map<String, GatewayProperties.ProviderConfig> configs = new LinkedHashMap<>();
        for (LlmProvider p : providers) {
            configs.put(p.name(), new GatewayProperties.ProviderConfig("http://localhost", p.defaultModel(), "", 5));
        }
        GatewayProperties properties = new GatewayProperties(providers[0].name(), configs);
        return new ProviderHealthService(new ProviderRegistry(List.of(providers), properties));
    }

    @Test
    void un_adaptateur_sans_sonde_repond_UNKNOWN_pas_DOWN() {
        List<ProviderHealth> health = service(new BareProvider("nu")).checkAll();

        assertThat(health).singleElement()
                .extracting(ProviderHealth::status).isEqualTo(ProviderHealth.Status.UNKNOWN);
    }

    @Test
    void une_sonde_qui_explose_ne_fait_pas_tomber_la_page_de_statut() {
        List<ProviderHealth> health = service(new ExplodingProvider(), new BareProvider("nu")).checkAll();

        assertThat(health).hasSize(2);
        assertThat(health).anyMatch(h -> h.status() == ProviderHealth.Status.DOWN
                && h.detail().contains("IllegalStateException"));
    }

    @Test
    void un_fournisseur_joignable_sans_son_modele_est_DEGRADED() {
        ProviderHealth health = ProviderHealth.up("x", "modele-absent", false, 10, "modele non installe");

        assertThat(health.status()).isEqualTo(ProviderHealth.Status.DEGRADED);
    }

    private static class BareProvider implements LlmProvider {
        private final String name;
        BareProvider(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String defaultModel() { return "m"; }
        @Override public ChatResult chat(ChatCall call) { return ChatResult.of("x", "m", new Usage(1, 1), "stop"); }
    }

    private static final class ExplodingProvider extends BareProvider {
        ExplodingProvider() { super("explosif"); }
        @Override public ProviderHealth health() { throw new IllegalStateException("sonde cassee"); }
    }
}

package com.aimastery.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aimastery.gateway.config.GatewayProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderRegistryTest {

    private static final GatewayProperties PROPERTIES = new GatewayProperties(
            "fake",
            Map.of("fake", new GatewayProperties.ProviderConfig(
                    "http://localhost", "fake-model", "", 5)));

    @Test
    void resout_le_fournisseur_par_defaut_quand_la_requete_n_en_precise_aucun() {
        ProviderRegistry registry = new ProviderRegistry(List.of(new FakeProvider()), PROPERTIES);

        assertThat(registry.resolve(null).name()).isEqualTo("fake");
        assertThat(registry.resolve("").name()).isEqualTo("fake");
    }

    @Test
    void refuse_un_fournisseur_inconnu() {
        ProviderRegistry registry = new ProviderRegistry(List.of(new FakeProvider()), PROPERTIES);

        assertThatThrownBy(() -> registry.resolve("inexistant"))
                .isInstanceOf(ProviderNotFoundException.class)
                .hasMessageContaining("fake");
    }

    @Test
    void refuse_de_demarrer_si_le_fournisseur_par_defaut_n_est_pas_charge() {
        GatewayProperties bad = new GatewayProperties(
                "absent",
                Map.of("fake", new GatewayProperties.ProviderConfig(
                        "http://localhost", "fake-model", "", 5)));

        assertThatThrownBy(() -> new ProviderRegistry(List.of(new FakeProvider()), bad))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-provider");
    }

    @Test
    void l_ordre_explicite_prime_sur_l_ordre_de_la_map_de_configuration() {
        GatewayProperties properties = new GatewayProperties("local", Map.of(
                "local", new GatewayProperties.ProviderConfig("http://l", "m", "", 5),
                "distant-1", new GatewayProperties.ProviderConfig("http://d1", "m", "", 5),
                "distant-2", new GatewayProperties.ProviderConfig("http://d2", "m", "", 5)),
                List.of("local", "distant-2", "distant-1"));

        ProviderRegistry registry = new ProviderRegistry(
                List.of(named("distant-1"), named("distant-2"), named("local")), properties);

        assertThat(registry.available()).containsExactly("local", "distant-2", "distant-1");
    }

    @Test
    void l_ordre_est_celui_de_la_configuration_pas_celui_de_decouverte_des_beans() {
        GatewayProperties properties = new GatewayProperties("local", new java.util.LinkedHashMap<>(Map.of()));
        properties.providers().put("local", new GatewayProperties.ProviderConfig("http://l", "m", "", 5));
        properties.providers().put("distant-1", new GatewayProperties.ProviderConfig("http://d1", "m", "", 5));
        properties.providers().put("distant-2", new GatewayProperties.ProviderConfig("http://d2", "m", "", 5));

        ProviderRegistry registry = new ProviderRegistry(
                List.of(named("distant-2"), named("local"), named("distant-1")), properties);

        // C'est aussi l'ordre de repli : il ne doit pas dependre du scan des composants.
        assertThat(registry.available()).containsExactly("local", "distant-1", "distant-2");
    }

    private static LlmProvider named(String name) {
        return new LlmProvider() {
            @Override public String name() { return name; }
            @Override public String defaultModel() { return "m"; }
            @Override public ChatResult chat(ChatCall call) { return ChatResult.of("x", "m", new Usage(1, 1), "stop"); }
        };
    }

    private static final class FakeProvider implements LlmProvider {

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public String defaultModel() {
            return "fake-model";
        }

        @Override
        public ChatResult chat(ChatCall call) {
            return ChatResult.of("reponse", call.model(), new Usage(1, 2), "stop");
        }
    }
}

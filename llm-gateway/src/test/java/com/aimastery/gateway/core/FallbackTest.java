package com.aimastery.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aimastery.gateway.config.GatewayProperties;
import com.aimastery.gateway.observability.CallLogger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FallbackTest {

    private static final List<Message> QUESTION = List.of(Message.user("bonjour"));

    private ChatService service(LlmProvider... providers) {
        Map<String, GatewayProperties.ProviderConfig> configs = new LinkedHashMap<>();
        for (LlmProvider provider : providers) {
            configs.put(provider.name(), new GatewayProperties.ProviderConfig(
                    "http://localhost", provider.defaultModel(), "", 5));
        }
        GatewayProperties properties = new GatewayProperties(providers[0].name(), configs);
        ProviderRegistry registry = new ProviderRegistry(List.of(providers), properties);
        return new ChatService(registry, new CallLogger(), new FallbackManager(registry, properties));
    }

    @Test
    void bascule_sur_le_fournisseur_suivant_quand_le_premier_tombe() {
        ChatService service = service(
                new StubProvider("primaire", "modele-primaire", true),
                new StubProvider("secours", "modele-secours", false));

        ChatService.ChatOutcome outcome = service.chat(null, null, QUESTION, ChatOptions.defaults());

        assertThat(outcome.provider()).isEqualTo("secours");
        assertThat(outcome.requestedProvider()).isEqualTo("primaire");
        assertThat(outcome.fallbackUsed()).isTrue();
        assertThat(outcome.result().content()).isEqualTo("reponse de secours");
    }

    @Test
    void ne_bascule_pas_quand_le_premier_repond() {
        ChatService service = service(
                new StubProvider("primaire", "modele-primaire", false),
                new StubProvider("secours", "modele-secours", false));

        ChatService.ChatOutcome outcome = service.chat(null, null, QUESTION, ChatOptions.defaults());

        assertThat(outcome.provider()).isEqualTo("primaire");
        assertThat(outcome.fallbackUsed()).isFalse();
    }

    @Test
    void le_repli_utilise_son_propre_modele_pas_celui_demande() {
        StubProvider secours = new StubProvider("secours", "modele-secours", false);
        ChatService service = service(new StubProvider("primaire", "modele-primaire", true), secours);

        // "modele-primaire-special" n'existe que chez le primaire : le repli ne doit
        // surtout pas le reutiliser.
        service.chat("primaire", "modele-primaire-special", QUESTION, ChatOptions.defaults());

        assertThat(secours.lastModel).isEqualTo("modele-secours");
    }

    @Test
    void le_repli_recoit_ses_propres_defauts_globaux_pas_ceux_du_primaire() {
        StubProvider secours = new StubProvider("secours", "modele-secours", false);
        StubProvider primaire = new StubProvider("primaire", "modele-primaire", true);
        Map<String, GenerationDefaults> global = Map.of(
                "primaire", new GenerationDefaults(null, 100, null),
                "secours", new GenerationDefaults(false, 2048, null));
        GatewayProperties properties = new GatewayProperties("primaire", new LinkedHashMap<>(Map.of(
                "primaire", new GatewayProperties.ProviderConfig("http://localhost", "modele-primaire", "", 5),
                "secours", new GatewayProperties.ProviderConfig("http://localhost", "modele-secours", "", 5))));
        ProviderRegistry registry = new ProviderRegistry(List.of(primaire, secours), properties);
        ChatService service = new ChatService(registry, new CallLogger(),
                new FallbackManager(registry, properties), CostEstimator.disabled(),
                provider -> global.getOrDefault(provider, GenerationDefaults.NONE));

        ChatService.ChatOutcome outcome = service.chat("primaire", null, QUESTION, ChatOptions.defaults());

        assertThat(secours.lastOptions).isEqualTo(new ChatOptions(null, null, 2048, false));
        assertThat(outcome.applied().maxTokens().origin()).isEqualTo(AppliedOptions.Origin.GLOBAL);
    }

    @Test
    void un_fournisseur_inconnu_est_une_erreur_client_pas_une_panne() {
        ChatService service = service(
                new StubProvider("primaire", "modele-primaire", false),
                new StubProvider("secours", "modele-secours", false));

        assertThatThrownBy(() -> service.chat("openai", null, QUESTION, ChatOptions.defaults()))
                .isInstanceOf(ProviderNotFoundException.class);
    }

    @Test
    void echoue_explicitement_quand_toute_la_chaine_tombe() {
        ChatService service = service(
                new StubProvider("primaire", "modele-primaire", true),
                new StubProvider("secours", "modele-secours", true));

        assertThatThrownBy(() -> service.chat(null, null, QUESTION, ChatOptions.defaults()))
                .isInstanceOf(AllProvidersFailedException.class)
                .hasMessageContaining("primaire")
                .hasMessageContaining("secours");
    }

    private static final class StubProvider implements LlmProvider {

        private final String name;
        private final String defaultModel;
        private final boolean alwaysFails;
        private String lastModel;
        private ChatOptions lastOptions;

        StubProvider(String name, String defaultModel, boolean alwaysFails) {
            this.name = name;
            this.defaultModel = defaultModel;
            this.alwaysFails = alwaysFails;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String defaultModel() {
            return defaultModel;
        }

        @Override
        public ChatResult chat(ChatCall call) {
            this.lastModel = call.model();
            this.lastOptions = call.options();
            if (alwaysFails) {
                throw new ProviderCallException(name, "panne simulee", null);
            }
            return ChatResult.of("reponse de " + name, call.model(), new Usage(5, 7), "stop");
        }
    }
}

package com.aimastery.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.gateway.config.GatewayProperties;
import com.aimastery.gateway.observability.CallLogger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatServiceTest {

    private static final GatewayProperties PROPERTIES = new GatewayProperties(
            "fake",
            Map.of("fake", new GatewayProperties.ProviderConfig(
                    "http://localhost", "fake-model", "", 5)));

    @Test
    void utilise_le_modele_par_defaut_du_fournisseur_quand_la_requete_n_en_donne_pas() {
        RecordingProvider provider = new RecordingProvider();
        ChatService service = new ChatService(
                new ProviderRegistry(List.of(provider), PROPERTIES), new CallLogger());

        service.chat(null, null, List.of(Message.user("bonjour")), ChatOptions.defaults());

        assertThat(provider.lastCall.model()).isEqualTo("fake-model");
    }

    @Test
    void le_modele_de_la_requete_prime_sur_celui_de_la_configuration() {
        RecordingProvider provider = new RecordingProvider();
        ChatService service = new ChatService(
                new ProviderRegistry(List.of(provider), PROPERTIES), new CallLogger());

        service.chat("fake", "autre-modele", List.of(Message.user("bonjour")), ChatOptions.defaults());

        assertThat(provider.lastCall.model()).isEqualTo("autre-modele");
    }

    @Test
    void mesure_la_latence_de_chaque_appel() {
        ChatService service = new ChatService(
                new ProviderRegistry(List.of(new RecordingProvider()), PROPERTIES), new CallLogger());

        ChatService.ChatOutcome outcome = service.chat(
                null, null, List.of(Message.user("bonjour")), ChatOptions.defaults());

        assertThat(outcome.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(outcome.provider()).isEqualTo("fake");
    }

    private static final class RecordingProvider implements LlmProvider {

        private ChatCall lastCall;

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
            this.lastCall = call;
            return ChatResult.of("reponse", call.model(), new Usage(10, 20), "stop");
        }
    }
}

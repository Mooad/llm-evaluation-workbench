package com.aimastery.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aimastery.gateway.config.GatewayProperties;
import com.aimastery.gateway.observability.CallLogger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class StreamingTest {

    private static final List<Message> QUESTION = List.of(Message.user("bonjour"));

    private final List<String> received = new ArrayList<>();

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
    void transmet_chaque_fragment_puis_le_resultat_complet() {
        ChatService service = service(new StreamingProvider("primaire", List.of("Nouak", "chott"), false));

        ChatService.ChatOutcome outcome = service.stream(null, null, QUESTION, ChatOptions.defaults(), received::add);

        assertThat(received).containsExactly("Nouak", "chott");
        assertThat(outcome.result().content()).isEqualTo("Nouakchott");
    }

    @Test
    void bascule_si_le_primaire_tombe_avant_le_premier_fragment() {
        ChatService service = service(
                new StreamingProvider("primaire", List.of(), true),
                new StreamingProvider("secours", List.of("ok"), false));

        ChatService.ChatOutcome outcome = service.stream(null, null, QUESTION, ChatOptions.defaults(), received::add);

        assertThat(outcome.provider()).isEqualTo("secours");
        assertThat(outcome.fallbackUsed()).isTrue();
        assertThat(received).containsExactly("ok");
    }

    @Test
    void ne_bascule_plus_une_fois_le_flux_commence() {
        StreamingProvider secours = new StreamingProvider("secours", List.of("autre reponse"), false);
        ChatService service = service(new StreamingProvider("primaire", List.of("debut"), true), secours);

        // Basculer ici collerait "debut" et "autre reponse" dans le meme flux.
        assertThatThrownBy(() -> service.stream(null, null, QUESTION, ChatOptions.defaults(), received::add))
                .isInstanceOf(ProviderCallException.class);
        assertThat(received).containsExactly("debut");
        assertThat(secours.called).isFalse();
    }

    @Test
    void un_adaptateur_sans_streaming_envoie_la_reponse_en_un_fragment() {
        LlmProvider plain = new LlmProvider() {
            @Override public String name() { return "simple"; }
            @Override public String defaultModel() { return "m"; }
            @Override public ChatResult chat(ChatCall call) {
                return ChatResult.of("reponse entiere", "m", new Usage(1, 2), "stop");
            }
        };

        service(plain).stream(null, null, QUESTION, ChatOptions.defaults(), received::add);

        assertThat(received).containsExactly("reponse entiere");
    }

    /** Emet ses fragments, puis echoue si demande. */
    private static final class StreamingProvider implements LlmProvider {
        private final String name;
        private final List<String> deltas;
        private final boolean failsAfterDeltas;
        private boolean called;

        StreamingProvider(String name, List<String> deltas, boolean failsAfterDeltas) {
            this.name = name;
            this.deltas = deltas;
            this.failsAfterDeltas = failsAfterDeltas;
        }

        @Override public String name() { return name; }
        @Override public String defaultModel() { return name + "-model"; }

        @Override
        public ChatResult chat(ChatCall call) {
            throw new UnsupportedOperationException("ce test ne passe que par stream");
        }

        @Override
        public ChatResult stream(ChatCall call, Consumer<String> onDelta) {
            called = true;
            deltas.forEach(onDelta);
            if (failsAfterDeltas) {
                throw new ProviderCallException(name, "panne simulee", null);
            }
            return ChatResult.of(String.join("", deltas), call.model(), new Usage(3, deltas.size()), "stop");
        }
    }
}

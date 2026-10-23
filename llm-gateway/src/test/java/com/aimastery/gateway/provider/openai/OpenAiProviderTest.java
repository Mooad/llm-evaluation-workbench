package com.aimastery.gateway.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.gateway.config.GatewayProperties;
import com.aimastery.gateway.config.RestClientConfig;
import com.aimastery.gateway.core.ChatCall;
import com.aimastery.gateway.core.ChatOptions;
import com.aimastery.gateway.core.ChatResult;
import com.aimastery.gateway.core.Message;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Faux serveur OpenAI local : on verifie le corps tel qu'il part sur le fil. */
class OpenAiProviderTest {

    private HttpServer server;
    private String lastAuthorization;
    private String lastBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = """
                    {"model":"gpt-4.1-mini-2025-04-14",
                     "choices":[{"message":{"role":"assistant","content":"Rabat"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":12,"completion_tokens":2}}""".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void envoie_max_completion_tokens_et_jamais_l_extension_thinking_de_deepseek() {
        provider().chat(call(new ChatOptions(0.0, null, 300, false)));

        assertThat(lastAuthorization).isEqualTo("Bearer sk-test");
        assertThat(lastBody).contains("\"max_completion_tokens\":300").contains("\"temperature\":0.0");
        // OpenAI refuse un champ inconnu : le reglage raisonnement ne doit rien ajouter ici.
        assertThat(lastBody).doesNotContain("max_tokens\":").doesNotContain("thinking");
    }

    @Test
    void lit_la_reponse_et_garde_la_version_datee_du_modele() {
        ChatResult result = provider().chat(call(ChatOptions.defaults()));

        assertThat(result.content()).isEqualTo("Rabat");
        assertThat(result.model()).isEqualTo("gpt-4.1-mini-2025-04-14");
        assertThat(result.usage().inputTokens()).isEqualTo(12);
    }

    @Test
    void ne_gere_pas_le_mode_raisonnement() {
        assertThat(provider().supportsReasoning()).isFalse();
    }

    private OpenAiProvider provider() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        GatewayProperties properties = new GatewayProperties(OpenAiProvider.NAME, Map.of(
                OpenAiProvider.NAME, new GatewayProperties.ProviderConfig(baseUrl, "gpt-4.1-mini", "sk-test", 5)));
        return new OpenAiProvider(properties, new RestClientConfig().restClientFactory());
    }

    private static ChatCall call(ChatOptions options) {
        return new ChatCall("gpt-4.1-mini", List.of(Message.user("Capitale du Maroc ?")), options);
    }
}

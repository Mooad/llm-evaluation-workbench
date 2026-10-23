package com.aimastery.gateway.provider.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aimastery.gateway.config.GatewayProperties;
import com.aimastery.gateway.config.RestClientConfig;
import com.aimastery.gateway.core.ChatCall;
import com.aimastery.gateway.core.ChatOptions;
import com.aimastery.gateway.core.ChatResult;
import com.aimastery.gateway.core.FinishReason;
import com.aimastery.gateway.core.Message;
import com.aimastery.gateway.core.ProviderCallException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Faux serveur Ollama local : on verifie le NDJSON tel qu'il passe sur le fil. */
class OllamaProviderTest {

    private HttpServer server;
    private String lastBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void lit_le_flux_ndjson_et_prend_l_usage_sur_la_derniere_ligne() {
        respond("""
                {"model":"qwen3-coder:30b","message":{"role":"assistant","content":"Nouak"},"done":false}
                {"model":"qwen3-coder:30b","message":{"role":"assistant","content":"chott"},"done":false}
                {"model":"qwen3-coder:30b","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":14,"eval_count":3}
                """);
        List<String> deltas = new ArrayList<>();

        ChatResult result = provider().stream(call(), deltas::add);

        assertThat(deltas).containsExactly("Nouak", "chott");
        assertThat(result.content()).isEqualTo("Nouakchott");
        assertThat(result.usage().inputTokens()).isEqualTo(14);
        assertThat(result.usage().outputTokens()).isEqualTo(3);
        assertThat(result.finishReason()).isEqualTo(FinishReason.COMPLETE);
        assertThat(lastBody).contains("\"stream\":true");
    }

    @Test
    void une_erreur_en_plein_flux_est_une_panne_fournisseur() {
        respond("""
                {"model":"m","message":{"role":"assistant","content":"debut"},"done":false}
                {"error":"model runner has unexpectedly stopped"}
                """);

        assertThatThrownBy(() -> provider().stream(call(), delta -> { }))
                .isInstanceOf(ProviderCallException.class)
                .hasMessageContaining("unexpectedly stopped");
    }

    private OllamaProvider provider() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        GatewayProperties properties = new GatewayProperties(OllamaProvider.NAME, Map.of(
                OllamaProvider.NAME, new GatewayProperties.ProviderConfig(baseUrl, "qwen3-coder:30b", "", 5)));
        return new OllamaProvider(properties, new RestClientConfig().restClientFactory());
    }

    private static ChatCall call() {
        return new ChatCall("qwen3-coder:30b", List.of(Message.user("Capitale de la Mauritanie ?")),
                ChatOptions.defaults());
    }

    private void respond(String ndjson) {
        server.createContext("/api/chat", exchange -> {
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = ndjson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }
}

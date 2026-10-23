package com.aimastery.gateway.provider.deepseek;

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
import com.aimastery.gateway.core.ProviderHealth;
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

/**
 * Faux serveur HTTP local plutot qu'un mock du RestClient : on verifie ce qui passe
 * reellement sur le fil, noms de champs JSON compris.
 */
class DeepSeekProviderTest {

    private HttpServer server;
    private String lastAuthorization;
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
    void traduit_la_reponse_au_format_openai_vers_le_domaine() {
        respond("/chat/completions", 200, """
                {"model":"deepseek-flash",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"Nouakchott"},
                             "finish_reason":"length"}],
                 "usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}""");

        ChatResult result = provider("sk-test").chat(call(new ChatOptions(null, null, 5, null)));

        assertThat(result.content()).isEqualTo("Nouakchott");
        assertThat(result.model()).isEqualTo("deepseek-flash");
        assertThat(result.usage().inputTokens()).isEqualTo(12);
        assertThat(result.usage().outputTokens()).isEqualTo(3);
        assertThat(result.finishReason()).isEqualTo(FinishReason.TRUNCATED);
        assertThat(result.providerFinishReason()).isEqualTo("length");
    }

    @Test
    void envoie_la_cle_en_bearer_et_n_impose_pas_les_options_absentes() {
        respond("/chat/completions", 200, """
                {"model":"m","choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""");

        provider("sk-test").chat(call(new ChatOptions(null, null, 50, null)));

        assertThat(lastAuthorization).isEqualTo("Bearer sk-test");
        assertThat(lastBody).contains("\"max_tokens\":50").contains("\"stream\":false");
        assertThat(lastBody).doesNotContain("temperature").doesNotContain("top_p").doesNotContain("thinking");
    }

    @Test
    void lit_le_flux_sse_et_recupere_l_usage_du_dernier_evenement() {
        respond("/chat/completions", 200, """
                : keep-alive

                data: {"model":"deepseek-flash","choices":[{"delta":{"reasoning_content":"je reflechis"},"finish_reason":null}]}

                data: {"model":"deepseek-flash","choices":[{"delta":{"content":"Nouak"},"finish_reason":null}]}

                data: {"model":"deepseek-flash","choices":[{"delta":{"content":"chott"},"finish_reason":"stop"}]}

                data: {"model":"deepseek-flash","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":40}}

                data: [DONE]

                """);
        List<String> deltas = new java.util.ArrayList<>();

        ChatResult result = provider("sk-test").stream(call(ChatOptions.defaults()), deltas::add);

        assertThat(deltas).containsExactly("Nouak", "chott");
        assertThat(result.content()).isEqualTo("Nouakchott");
        assertThat(result.usage().outputTokens()).isEqualTo(40);
        assertThat(result.finishReason()).isEqualTo(FinishReason.COMPLETE);
        assertThat(lastBody).contains("\"stream\":true").contains("\"include_usage\":true");
    }

    @Test
    void un_flux_coupe_avant_done_est_une_panne() {
        respond("/chat/completions", 200, """
                data: {"model":"m","choices":[{"delta":{"content":"debut"},"finish_reason":null}]}

                """);

        assertThatThrownBy(() -> provider("sk-test").stream(call(ChatOptions.defaults()), delta -> { }))
                .isInstanceOf(ProviderCallException.class)
                .hasMessageContaining("[DONE]");
    }

    @Test
    void desactive_le_raisonnement_quand_l_appelant_le_demande() {
        respond("/chat/completions", 200, """
                {"model":"m","choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""");

        provider("sk-test").chat(call(new ChatOptions(0.0, null, 300, false)));

        assertThat(lastBody).contains("\"thinking\":{\"type\":\"disabled\"}");
    }

    @Test
    void une_erreur_http_devient_une_panne_fournisseur_pour_declencher_le_fallback() {
        respond("/chat/completions", 503, "{\"error\":\"surcharge\"}");

        assertThatThrownBy(() -> provider("sk-test").chat(call(ChatOptions.defaults())))
                .isInstanceOf(ProviderCallException.class);
    }

    @Test
    void sans_cle_echoue_sans_appel_reseau() {
        assertThatThrownBy(() -> provider("").chat(call(ChatOptions.defaults())))
                .isInstanceOf(ProviderCallException.class)
                .hasMessageContaining("cle");
        assertThat(lastBody).isNull();
    }

    @Test
    void une_cle_refusee_rend_le_fournisseur_DEGRADED_pas_DOWN() {
        respond("/models", 401, "{\"error\":{\"type\":\"authentication_error\"}}");

        ProviderHealth health = provider("sk-invalide").health();

        assertThat(health.status()).isEqualTo(ProviderHealth.Status.DEGRADED);
        assertThat(health.detail()).contains("401");
    }

    @Test
    void la_sonde_verifie_que_le_modele_par_defaut_est_servi() {
        respond("/models", 200, "{\"data\":[{\"id\":\"deepseek-flash\"},{\"id\":\"deepseek-v4-pro\"}]}");

        assertThat(provider("sk-test").health().status()).isEqualTo(ProviderHealth.Status.UP);
    }

    private DeepSeekProvider provider(String apiKey) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        GatewayProperties properties = new GatewayProperties(DeepSeekProvider.NAME, Map.of(
                DeepSeekProvider.NAME,
                new GatewayProperties.ProviderConfig(baseUrl, "deepseek-flash", apiKey, 5)));
        return new DeepSeekProvider(properties, new RestClientConfig().restClientFactory());
    }

    private static ChatCall call(ChatOptions options) {
        return new ChatCall("deepseek-flash", List.of(Message.user("Capitale de la Mauritanie ?")), options);
    }

    private void respond(String path, int status, String json) {
        server.createContext(path, exchange -> {
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }
}

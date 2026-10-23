package com.aimastery.gateway.provider.openai;

import com.aimastery.gateway.config.GatewayProperties;
import com.aimastery.gateway.config.RestClientConfig.RestClientFactory;
import com.aimastery.gateway.core.ChatCall;
import com.aimastery.gateway.core.ChatOptions;
import com.aimastery.gateway.core.ChatResult;
import com.aimastery.gateway.core.LlmProvider;
import com.aimastery.gateway.core.ProviderCallException;
import com.aimastery.gateway.core.ProviderHealth;
import com.aimastery.gateway.core.Usage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Tout fournisseur qui parle le format OpenAI (/chat/completions, /models, SSE "data:") :
 * OpenAI lui-meme, DeepSeek, demain vLLM ou LM Studio.
 *
 * Une sous-classe ne declare que ce qui differe : son nom, le nom du champ de limite de
 * tokens, et ses extensions au format (ex. "thinking" chez DeepSeek). Le reste — appel,
 * flux, sonde, cle — est ecrit une seule fois.
 */
public abstract class OpenAiCompatibleProvider implements LlmProvider {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String name;
    private final RestClient client;
    private final String defaultModel;
    private final String baseUrl;
    private final boolean keyConfigured;

    protected OpenAiCompatibleProvider(String name, GatewayProperties properties, RestClientFactory factory) {
        GatewayProperties.ProviderConfig config = properties.provider(name);
        this.name = name;
        this.baseUrl = config.baseUrl();
        this.defaultModel = config.defaultModel();
        this.keyConfigured = config.apiKey() != null && !config.apiKey().isBlank();

        RestClient base = factory.create(config.baseUrl(), Duration.ofSeconds(config.timeoutSeconds()));
        this.client = keyConfigured
                ? base.mutate().defaultHeaders(headers -> headers.setBearerAuth(config.apiKey())).build()
                : base;
    }

    /** Nom du champ de limite de tokens : "max_tokens" historiquement, renomme chez OpenAI. */
    protected String maxTokensField() {
        return "max_tokens";
    }

    /** Champs propres au fournisseur, ajoutes au corps standard. Rien par defaut. */
    protected void extend(Map<String, Object> body, ChatOptions options) {
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final String defaultModel() {
        return defaultModel;
    }

    @Override
    public ChatResult chat(ChatCall call) {
        requireKey();
        try {
            ChatCompletionResponse response = client.post()
                    .uri("/chat/completions")
                    .body(body(call, false))
                    .retrieve()
                    .body(ChatCompletionResponse.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new ProviderCallException(name, "Reponse " + name + " vide", null);
            }

            ChatCompletionResponse.Choice choice = response.choices().get(0);
            return ChatResult.of(
                    choice.message() == null ? null : choice.message().content(),
                    response.model(),
                    toUsage(response.usage()),
                    choice.finishReason());

        } catch (RestClientException e) {
            throw new ProviderCallException(name, "Appel " + name + " en echec : " + e.getMessage(), e);
        }
    }

    @Override
    public ChatResult stream(ChatCall call, Consumer<String> onDelta) {
        requireKey();
        try {
            return client.post()
                    .uri("/chat/completions")
                    .body(body(call, true))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new ProviderCallException(name, "Flux " + name + " refuse : HTTP "
                                    + response.getStatusCode().value() + " "
                                    + new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8),
                                    null, response.getStatusCode().value());
                        }
                        return readStream(response.getBody(), onDelta);
                    });
        } catch (RestClientException e) {
            throw new ProviderCallException(name, "Flux " + name + " en echec : " + e.getMessage(), e);
        }
    }

    @Override
    public ProviderHealth health() {
        if (!keyConfigured) {
            return ProviderHealth.degraded(name, defaultModel, "aucune cle API configuree");
        }

        long start = System.nanoTime();
        try {
            ModelList models = client.get().uri("/models").retrieve().body(ModelList.class);
            long latencyMs = elapsedMs(start);

            if (models == null || models.data() == null) {
                return ProviderHealth.down(name, defaultModel, latencyMs, "reponse /models vide");
            }
            List<String> served = models.data().stream().map(ModelList.Model::id).toList();
            boolean available = served.contains(defaultModel);

            return ProviderHealth.up(name, defaultModel, available, latencyMs,
                    available
                            ? served.size() + " modele(s) servi(s)"
                            : "modele '" + defaultModel + "' absent — servis : " + served);

        } catch (HttpClientErrorException e) {
            // L'API a repondu : elle est joignable, c'est la cle qui pose probleme.
            if (e.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)
                    || e.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)) {
                return ProviderHealth.degraded(name, defaultModel,
                        "cle API refusee (" + e.getStatusCode().value() + ")");
            }
            return ProviderHealth.down(name, defaultModel, elapsedMs(start), e.getMessage());
        } catch (RestClientException e) {
            return ProviderHealth.down(name, defaultModel, elapsedMs(start),
                    "API injoignable sur " + baseUrl + " : " + e.getMessage());
        }
    }

    /**
     * Corps construit champ par champ : une option absente reste absente sur le fil, sinon
     * on imposerait "temperature: null" au lieu du defaut du fournisseur — et certains
     * modeles refusent meme un champ qu'ils ne gerent pas.
     */
    private Map<String, Object> body(ChatCall call, boolean stream) {
        ChatOptions options = call.options() == null ? ChatOptions.defaults() : call.options();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", call.model());
        body.put("messages", call.messages().stream()
                .map(m -> Map.of("role", m.role().wireName(), "content", m.content()))
                .toList());
        body.put("stream", stream);
        if (stream) {
            // Sans include_usage, un flux ne rapporte aucun token : cout inconnu.
            body.put("stream_options", Map.of("include_usage", true));
        }
        putIfPresent(body, "temperature", options.temperature());
        putIfPresent(body, "top_p", options.topP());
        putIfPresent(body, maxTokensField(), options.maxTokens());
        extend(body, options);
        return body;
    }

    /**
     * SSE au format OpenAI : lignes "data: {json}", commentaires ": keep-alive" pendant
     * que le modele reflechit, et "data: [DONE]" pour finir.
     */
    private ChatResult readStream(InputStream body, Consumer<String> onDelta) throws IOException {
        StringBuilder content = new StringBuilder();
        String model = null;
        String finishReason = null;
        Usage usage = Usage.unknown();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    return ChatResult.of(content.toString(), model, usage, finishReason);
                }
                ChatCompletionChunk chunk = JSON.readValue(data, ChatCompletionChunk.class);
                if (chunk.model() != null) {
                    model = chunk.model();
                }
                if (chunk.usage() != null) {
                    usage = toUsage(chunk.usage());
                }
                if (chunk.choices() == null || chunk.choices().isEmpty()) {
                    continue;
                }
                ChatCompletionChunk.Choice choice = chunk.choices().get(0);
                if (choice.finishReason() != null) {
                    finishReason = choice.finishReason();
                }
                String delta = choice.delta() == null ? null : choice.delta().content();
                if (delta != null && !delta.isEmpty()) {
                    content.append(delta);
                    onDelta.accept(delta);
                }
            }
        }
        throw new ProviderCallException(name, "Flux " + name + " coupe avant [DONE]", null);
    }

    private void requireKey() {
        if (!keyConfigured) {
            // Echec immediat plutot qu'un aller-retour reseau pour recevoir un 401 :
            // le fallback bascule tout de suite.
            throw new ProviderCallException(name, "aucune cle API configuree", null);
        }
    }

    private static void putIfPresent(Map<String, Object> body, String field, Object value) {
        if (value != null) {
            body.put(field, value);
        }
    }

    private static Usage toUsage(ChatCompletionResponse.WireUsage usage) {
        return usage == null
                ? Usage.unknown()
                : new Usage(usage.promptTokens(), usage.completionTokens());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}

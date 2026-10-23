package com.aimastery.gateway.provider.ollama;

import com.aimastery.gateway.config.GatewayProperties;
import com.aimastery.gateway.config.RestClientConfig.RestClientFactory;
import com.aimastery.gateway.core.ChatCall;
import com.aimastery.gateway.core.ChatResult;
import com.aimastery.gateway.core.LlmProvider;
import com.aimastery.gateway.core.Message;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adaptateur Ollama — modele local, aucune cle API.
 *
 * Il traduit dans les deux sens : domaine -> format Ollama, reponse Ollama -> domaine.
 * Le reste du Gateway ignore totalement l'existence d'Ollama.
 */
@Component
public class OllamaProvider implements LlmProvider {

    public static final String NAME = "ollama";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient client;
    private final String defaultModel;
    private final String baseUrl;

    public OllamaProvider(GatewayProperties properties, RestClientFactory factory) {
        GatewayProperties.ProviderConfig config = properties.provider(NAME);
        this.baseUrl = config.baseUrl();
        this.client = factory.create(config.baseUrl(), Duration.ofSeconds(config.timeoutSeconds()));
        this.defaultModel = config.defaultModel();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String defaultModel() {
        return defaultModel;
    }

    @Override
    public ChatResult chat(ChatCall call) {
        try {
            OllamaChatResponse response = client.post()
                    .uri("/api/chat")
                    .body(toRequest(call, false))
                    .retrieve()
                    .body(OllamaChatResponse.class);

            if (response == null || response.message() == null) {
                throw new ProviderCallException(NAME, "Reponse Ollama vide", null);
            }

            return ChatResult.of(
                    response.message().content(),
                    response.model(),
                    new Usage(response.promptEvalCount(), response.evalCount()),
                    response.doneReason());

        } catch (RestClientException e) {
            throw new ProviderCallException(NAME, "Appel Ollama en echec : " + e.getMessage(), e);
        }
    }

    @Override
    public ChatResult stream(ChatCall call, Consumer<String> onDelta) {
        try {
            return client.post()
                    .uri("/api/chat")
                    .body(toRequest(call, true))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new ProviderCallException(NAME, "Flux Ollama refuse : HTTP "
                                    + response.getStatusCode().value() + " " + readBody(response.getBody()),
                                    null, response.getStatusCode().value());
                        }
                        return readStream(response.getBody(), onDelta);
                    });
        } catch (RestClientException e) {
            throw new ProviderCallException(NAME, "Flux Ollama en echec : " + e.getMessage(), e);
        }
    }

    /** NDJSON : un objet JSON par ligne, le dernier a done = true. */
    private ChatResult readStream(InputStream body, Consumer<String> onDelta) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                OllamaChatResponse chunk = JSON.readValue(line, OllamaChatResponse.class);
                if (chunk.error() != null) {
                    throw new ProviderCallException(NAME, "Ollama a interrompu le flux : " + chunk.error(), null);
                }
                String delta = chunk.message() == null ? null : chunk.message().content();
                if (delta != null && !delta.isEmpty()) {
                    content.append(delta);
                    onDelta.accept(delta);
                }
                if (chunk.done()) {
                    return ChatResult.of(content.toString(), chunk.model(),
                            new Usage(chunk.promptEvalCount(), chunk.evalCount()), chunk.doneReason());
                }
            }
        }
        throw new ProviderCallException(NAME, "Flux Ollama coupe avant la fin (pas de ligne done)", null);
    }

    private static String readBody(InputStream body) throws IOException {
        return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Override
    public ProviderHealth health() {
        long start = System.nanoTime();
        try {
            OllamaTags tags = client.get().uri("/api/tags").retrieve().body(OllamaTags.class);
            long latencyMs = elapsedMs(start);

            if (tags == null || tags.models() == null) {
                return ProviderHealth.down(NAME, defaultModel, latencyMs, "reponse /api/tags vide");
            }
            List<String> installed = tags.models().stream().map(OllamaTags.Model::name).toList();
            boolean available = installed.contains(defaultModel);

            return ProviderHealth.up(NAME, defaultModel, available, latencyMs,
                    available
                            ? installed.size() + " modele(s) installe(s)"
                            : "modele '" + defaultModel + "' absent — installes : " + installed);

        } catch (RestClientException e) {
            return ProviderHealth.down(NAME, defaultModel, elapsedMs(start),
                    "serveur injoignable sur " + baseUrl + " : " + e.getMessage());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private OllamaChatRequest toRequest(ChatCall call, boolean stream) {
        List<OllamaMessage> messages = call.messages().stream()
                .map(this::toOllamaMessage)
                .toList();

        Map<String, Object> options = new HashMap<>();
        if (call.options() != null) {
            if (call.options().temperature() != null) {
                options.put("temperature", call.options().temperature());
            }
            if (call.options().topP() != null) {
                options.put("top_p", call.options().topP());
            }
            if (call.options().maxTokens() != null) {
                options.put("num_predict", call.options().maxTokens());
            }
        }

        return new OllamaChatRequest(call.model(), messages, stream, options);
    }

    private OllamaMessage toOllamaMessage(Message message) {
        return new OllamaMessage(message.role().wireName(), message.content());
    }
}

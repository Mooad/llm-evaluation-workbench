package com.aimastery.rag.embedding;

import com.aimastery.rag.config.RagProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Embeddings via l'API native d'Ollama (POST /api/embed), sans passer par Spring AI :
 * cette classe est provisoire, elle sera remplacee par un appel au Gateway, et un client
 * HTTP de 30 lignes se remplace plus facilement qu'une auto-configuration.
 */
public class OllamaEmbeddingClient implements EmbeddingClient {

    private final RestClient client;
    private final String model;

    public OllamaEmbeddingClient(RagProperties.Embedding config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        // Le premier appel charge le modele en memoire : plusieurs secondes, a ne pas confondre avec une panne.
        factory.setReadTimeout(Duration.ofSeconds(config.timeoutSeconds()));
        this.client = RestClient.builder().baseUrl(config.baseUrl()).requestFactory(factory).build();
        this.model = config.model();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        try {
            EmbedResponse response = client.post()
                    .uri("/api/embed")
                    .body(new EmbedRequest(model, texts))
                    .retrieve()
                    .body(EmbedResponse.class);

            if (response == null || response.embeddings() == null || response.embeddings().size() != texts.size()) {
                throw new EmbeddingUnavailableException(
                        "Reponse Ollama incomplete : " + texts.size() + " textes envoyes", null);
            }
            return response.embeddings();

        } catch (RestClientException e) {
            throw new EmbeddingUnavailableException(
                    "Ollama injoignable ou modele absent (" + model + ") : " + e.getMessage(), e);
        }
    }

    @Override
    public String model() {
        return model;
    }

    private record EmbedRequest(String model, List<String> input) {
    }

    private record EmbedResponse(String model, List<float[]> embeddings) {
    }
}

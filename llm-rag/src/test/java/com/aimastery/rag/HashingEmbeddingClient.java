package com.aimastery.rag;

import com.aimastery.rag.embedding.EmbeddingClient;
import java.text.Normalizer;
import java.util.List;

/**
 * Embedding de test : sac de mots hache, sans modele. Deterministe et lexical, donc assez pour
 * verifier que "pression des pneus" retrouve une page qui en parle, sans dependre d'Ollama.
 */
public class HashingEmbeddingClient implements EmbeddingClient {

    private static final int DIMENSIONS = 256;

    @Override
    public List<float[]> embed(List<String> texts) {
        return texts.stream().map(HashingEmbeddingClient::vector).toList();
    }

    @Override
    public String model() {
        return "hashing-test";
    }

    private static float[] vector(String text) {
        float[] vector = new float[DIMENSIONS];
        String plain = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        for (String word : plain.split("[^a-z0-9]+")) {
            // Les mots courts (de, la, les...) sont partout : ils rapprocheraient tout de tout.
            if (word.length() > 3) {
                vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1;
            }
        }
        // Un vecteur nul n'a pas de direction : Qdrant refuse de le comparer en cosine.
        vector[0] += 0.01f;
        return vector;
    }
}

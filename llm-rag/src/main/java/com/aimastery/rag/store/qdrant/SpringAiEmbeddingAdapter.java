package com.aimastery.rag.store.qdrant;

import com.aimastery.rag.embedding.EmbeddingClient;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Presente notre EmbeddingClient sous la forme attendue par le VectorStore de Spring AI.
 *
 * Le VectorStore calcule lui-meme les embeddings a l'ecriture et a la recherche : passer par
 * cet adaptateur garantit que les deux utilisent le meme modele, et que changer de source
 * d'embedding (Ollama -> Gateway) ne touche qu'a l'EmbeddingClient.
 */
public class SpringAiEmbeddingAdapter implements EmbeddingModel {

    private final EmbeddingClient client;
    private final boolean sectionInEmbedding;
    private volatile int dimensions;

    /**
     * @param sectionInEmbedding placer le titre de section devant le texte avant de le vectoriser.
     *                           Reglable parce que l'effet se mesure, et n'est pas toujours positif :
     *                           un titre vague ("Tableau de bord E") tire le chunk vers le chapitre.
     */
    public SpringAiEmbeddingAdapter(EmbeddingClient client, boolean sectionInEmbedding) {
        this.client = client;
        this.sectionInEmbedding = sectionInEmbedding;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<float[]> vectors = client.embed(request.getInstructions());
        List<Embedding> embeddings = new ArrayList<>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            embeddings.add(new Embedding(vectors.get(i), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    /**
     * Le titre de section, puis le texte. Pas les autres metadonnees : "page: 12" rapprocherait
     * des chunks sans rapport, alors que "Reinitialisation de la version Zigbee" situe un passage
     * dont le texte seul ("appuyez 5 fois sur plus") ne dit pas de quoi il parle.
     */
    @Override
    public String getEmbeddingContent(Document document) {
        Object section = sectionInEmbedding ? document.getMetadata().get(QdrantPayload.SECTION) : null;
        return section == null ? document.getText() : section + "\n" + document.getText();
    }

    /**
     * Mesuree une fois sur le vrai modele plutot que configuree : une dimension fausse en
     * configuration ferait echouer chaque ecriture dans Qdrant, loin de la cause.
     */
    @Override
    public int dimensions() {
        if (dimensions == 0) {
            dimensions = embed("dimension").length;
        }
        return dimensions;
    }
}

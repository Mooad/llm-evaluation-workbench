package com.aimastery.rag.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Toute la configuration du module. Changer de modele d'embedding, de collection ou de
 * taille de chunk ne demande aucune modification de code.
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(List<IndexConfig> indexes, Qdrant qdrant, Embedding embedding, Ingestion ingestion,
                            Registry registry, Storage storage, Evaluation evaluation) {

    /**
     * Un index : une strategie de decoupage, rangee dans sa propre collection Qdrant.
     *
     * @param strategy  "token" (fenetres de chunkSize tokens, chevauchement overlap) ou "section"
     *                  (une section du sommaire par chunk, au plus chunkSize tokens)
     * @param chunkSize en tokens cl100k (encodage du TokenTextSplitter de Spring AI)
     */
    public record IndexConfig(String name, String label, String collection, String strategy, int chunkSize, int overlap) {
    }

    /**
     * Le client Java de Qdrant parle gRPC : c'est le port 6334, pas le 6333 (HTTP).
     *
     * @param collectionPrefix ajoute devant la collection de chaque index : une instance de test ou
     *                         d'evaluation garde ses propres collections. Un prefixe plutot qu'une
     *                         surcharge de rag.indexes[i].collection : Spring remplace alors toute
     *                         la liste, et les autres reglages de l'index seraient perdus.
     */
    public record Qdrant(String host, int port, String collectionPrefix) {

        public String collection(String name) {
            return (collectionPrefix == null ? "" : collectionPrefix) + name;
        }
    }

    /** @param filesDir fichiers importes, conserves pour pouvoir reindexer sans reimporter */
    public record Storage(String filesDir) {
    }

    /** @param sectionInEmbedding titre de section devant le texte vectorise ; changer la valeur demande de reindexer */
    public record Embedding(String baseUrl, String model, int timeoutSeconds, boolean sectionInEmbedding) {
    }

    public record Ingestion(int threads) {
    }

    /** Chemin relatif = relatif au dossier de lancement (la racine du projet avec mvn ou IntelliJ). */
    public record Registry(String file) {
    }

    /**
     * @param dataset    jeu de questions, sur le classpath
     * @param reportsDir rapports d'evaluation (un JSON par execution), relatif au dossier de lancement
     */
    public record Evaluation(String dataset, String reportsDir) {
    }
}

package com.aimastery.rag.embedding;

import java.util.List;

/**
 * Le port vers le modele d'embedding.
 *
 * Aujourd'hui Ollama en direct ; demain POST /embeddings du Gateway, pour que tous les appels
 * modeles passent par le meme point de mesure. Le reste du module ne doit dependre que d'ici.
 */
public interface EmbeddingClient {

    /** Un vecteur par texte, dans le meme ordre que les textes recus. */
    List<float[]> embed(List<String> texts);

    /** Nom du modele, pour les logs : un index n'a de sens qu'avec le modele qui l'a produit. */
    String model();
}

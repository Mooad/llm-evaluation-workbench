package com.aimastery.rag.chunk;

import java.util.List;

/**
 * Le port vers la base vectorielle : ecrire les chunks d'un document, retrouver les plus proches.
 *
 * Le pipeline et la recherche ne connaissent que cette interface. Qdrant en est une
 * implementation ; MongoDB Atlas Vector Search (pressenti pour Moviid) en serait une autre,
 * sans toucher a l'ingestion. Les embeddings sont l'affaire de l'implementation : elle les
 * calcule avec le meme modele a l'ecriture et a la recherche.
 */
public interface ChunkStore {

    /**
     * Remplace tous les chunks du document par ceux-ci. Idempotent : rejouer le meme appel
     * laisse la base dans le meme etat.
     */
    void replace(String documentId, List<Chunk> chunks);

    /** Retire tous les chunks du document. Sans effet s'il n'en a aucun. */
    void delete(String documentId);

    /** Les K chunks les plus proches de la question, parmi ceux que le filtre laisse passer. */
    List<ScoredChunk> search(String query, SearchFilter filter, int topK);
}

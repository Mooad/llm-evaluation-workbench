package com.aimastery.rag.search;

import com.aimastery.rag.chunk.SourceLocation;

/**
 * Un chunk retrouve.
 *
 * @param location ou citer : {"type":"page","page":365} pour un PDF, {"type":"timeRange",...} pour une video
 * @param citation la meme chose en clair, prete a afficher : "notice.pdf, page 365"
 * @param section  titre de la section du document (sommaire PDF) ; null si le document n'a pas de structure
 * @param score    similarite cosine entre la question et le chunk (1 = identique), telle que
 *                 Qdrant la calcule : un classement, pas une probabilite de pertinence
 * @param excerpt  debut du chunk, pour l'affichage
 * @param content  chunk complet, ce qui sera injecte dans le prompt a l'etape suivante
 */
public record SearchHit(
        String documentId,
        String fileName,
        String sourceType,
        int chunkIndex,
        SourceLocation location,
        String section,
        String citation,
        double score,
        String excerpt,
        String content) {
}

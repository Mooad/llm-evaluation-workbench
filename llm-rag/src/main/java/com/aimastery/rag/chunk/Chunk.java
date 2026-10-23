package com.aimastery.rag.chunk;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Un passage indexe, tel que le module le connait, independamment de la base vectorielle.
 *
 * @param sourceType format de la source ("pdf"...), pour filtrer ou afficher differemment
 * @param chunkIndex position dans le document, continue de 0 a n-1
 * @param section    titre de la section du document ("Reinitialisation de la version Zigbee") ;
 *                   null si le document n'a pas de structure lisible
 */
public record Chunk(
        String id,
        String documentId,
        int chunkIndex,
        String fileName,
        String sourceType,
        String text,
        SourceLocation location,
        String section) {

    /**
     * Identifiant derive du document et de la position : reindexer le meme document ecrase
     * les memes chunks au lieu d'en ajouter. Un UUID, parce que Qdrant l'exige et que les
     * autres bases acceptent n'importe quelle chaine.
     */
    public static String idFor(String documentId, int chunkIndex) {
        return UUID.nameUUIDFromBytes((documentId + "#" + chunkIndex).getBytes(StandardCharsets.UTF_8)).toString();
    }
}

package com.aimastery.rag.index;

import com.aimastery.rag.chunk.ChunkStore;
import com.aimastery.rag.ingestion.IngestionPipeline;
import com.aimastery.rag.ingestion.source.SourceFormats;

/**
 * Un index : une strategie de decoupage et la collection ou ses chunks sont ranges.
 *
 * Deux index sur les memes documents permettent de comparer deux strategies a donnees egales :
 * meme evaluation, meme modele d'embedding, seul le decoupage change.
 *
 * @param name        identifiant court ("A"), stocke dans le registre et les rapports
 * @param label       nom lisible ("Tokens 400/80")
 * @param description reglages effectifs du decoupage ("token(400/80)"), ecrits dans les rapports
 * @param formats     les formats acceptes, chacun avec le chunker de cet index
 */
public record SearchIndex(
        String name,
        String label,
        String description,
        String collection,
        SourceFormats formats,
        ChunkStore store,
        IngestionPipeline pipeline) {
}

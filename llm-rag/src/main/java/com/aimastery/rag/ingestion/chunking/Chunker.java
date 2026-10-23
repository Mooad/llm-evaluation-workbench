package com.aimastery.rag.ingestion.chunking;

import com.aimastery.rag.ingestion.source.ExtractedDocument;
import java.util.List;

/**
 * Transform (strategie) : texte localise -> passages a indexer.
 *
 * Recoit tout le document, pas une section a la fois : un chunker de transcription doit
 * pouvoir regrouper plusieurs segments courts dans une fenetre de 30 secondes.
 */
public interface Chunker {

    /** Dans l'ordre de la source. Chaque passage garde une localisation citable. */
    List<ChunkDraft> chunk(ExtractedDocument document);

    /** Reglages effectifs ("token(400/80)"), ecrits dans les logs : un index se compare a reglages egaux. */
    String description();
}

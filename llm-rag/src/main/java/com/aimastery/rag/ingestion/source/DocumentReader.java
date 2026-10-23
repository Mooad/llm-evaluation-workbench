package com.aimastery.rag.ingestion.source;

/**
 * Extract (strategie) : un format de fichier -> du texte localise.
 *
 * Un reader par format (PDF, transcription, HTML...). Le pipeline ne sait pas lequel il
 * utilise : ajouter un format, c'est ajouter un reader, sans toucher au reste.
 */
public interface DocumentReader {

    /** Vrai si ce reader reconnait le contenu. Doit etre rapide : appele avant l'ingestion, en synchrone. */
    boolean supports(SourceFile file);

    ExtractedDocument read(SourceFile file);
}

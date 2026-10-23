package com.aimastery.rag.ingestion.source;

import com.aimastery.rag.chunk.SourceLocation;

/**
 * Un morceau de texte extrait, avec sa localisation : une page de PDF, un segment de
 * transcription. Le chunker ne melange jamais deux localisations dans un meme chunk
 * sans savoir les fusionner (c'est son contrat, pas celui du reader).
 *
 * @param title titre de la section du document qui contient ce texte ("Garantie"), tire de sa
 *              structure (sommaire PDF) ; null quand le document n'en a pas
 */
public record Section(String text, SourceLocation location, String title) {
}

package com.aimastery.rag.ingestion.source;

import java.util.List;

/** Ce qu'un reader sait tirer d'un fichier : du texte localise, dans l'ordre de la source. */
public record ExtractedDocument(List<Section> sections) {
}

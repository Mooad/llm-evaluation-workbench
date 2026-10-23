package com.aimastery.rag.ingestion;

/** @param sections sections extraites avec du texte (pages pour un PDF) */
public record IngestionResult(int sections, int chunks) {
}

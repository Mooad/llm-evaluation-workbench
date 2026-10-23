package com.aimastery.rag.chunk;

/** @param score similarite entre la question et le chunk, calculee par la base (cosine pour Qdrant) */
public record ScoredChunk(Chunk chunk, double score) {
}

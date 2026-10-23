package com.aimastery.rag.ingestion.chunking;

import com.aimastery.rag.chunk.SourceLocation;

/** Un passage decoupe, pas encore rattache a un document : le pipeline lui donne son identite. */
public record ChunkDraft(String text, SourceLocation location, String section) {
}

package com.aimastery.rag.ingestion.source;

import com.aimastery.rag.ingestion.chunking.Chunker;

/**
 * Un format que l'on sait ingerer : comment le lire, et comment le decouper.
 *
 * Reader et chunker vont par paire parce que le bon decoupage depend de la source : des
 * fenetres de tokens pour un PDF, des fenetres de temps pour une transcription. Les declarer
 * ensemble rend impossible un format lisible mais sans decoupage.
 *
 * @param type identifiant stable ("pdf"), stocke avec chaque chunk et dans le registre
 */
public record SourceFormat(String type, DocumentReader reader, Chunker chunker) {
}

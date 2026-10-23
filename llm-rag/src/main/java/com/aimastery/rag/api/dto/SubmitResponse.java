package com.aimastery.rag.api.dto;

import com.aimastery.rag.document.DocumentStatus;

/** @param alreadyKnown vrai si ce contenu etait deja indexe ou en cours : aucune ingestion relancee */
public record SubmitResponse(String documentId, DocumentStatus status, boolean alreadyKnown) {
}

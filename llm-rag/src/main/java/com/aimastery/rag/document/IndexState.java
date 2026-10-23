package com.aimastery.rag.document;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/**
 * Etat d'un document dans un index (une strategie de decoupage). sections et chunks restent nuls
 * tant que l'indexation n'est pas finie : un 0 ferait croire a un fichier vide.
 *
 * @param sections sections extraites avec du texte : pages pour un PDF
 */
public record IndexState(DocumentStatus status, Integer sections, Integer chunks, String error, Instant completedAt) {

    public static IndexState processing() {
        return new IndexState(DocumentStatus.PROCESSING, null, null, null, null);
    }

    public static IndexState ready(int sections, int chunks, Instant now) {
        return new IndexState(DocumentStatus.READY, sections, chunks, null, now);
    }

    public static IndexState failed(String error, Instant now) {
        return new IndexState(DocumentStatus.FAILED, null, null, error, now);
    }

    @JsonIgnore
    public boolean isReady() {
        return status == DocumentStatus.READY;
    }
}

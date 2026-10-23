package com.aimastery.rag.document;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Un document, et son etat dans chaque index.
 *
 * Un meme document est indexe par plusieurs strategies de decoupage (index A, B...) pour les
 * comparer a donnees egales : d'ou un etat par index, plutot qu'un statut unique.
 *
 * @param sourceType format reconnu ("pdf"...)
 * @param collection groupe de documents choisi a l'import ("moviid", "rh"...) pour restreindre la
 *                   recherche ; null = aucun groupe
 * @param indexes    etat par nom d'index, dans l'ordre de la configuration
 * @param fileStored vrai si le fichier d'origine est conserve : sans lui, impossible de reindexer
 *                   sans le reimporter
 */
public record DocumentRecord(
        String documentId,
        String fileName,
        String sourceType,
        String collection,
        String sha256,
        Map<String, IndexState> indexes,
        boolean fileStored,
        Instant submittedAt) {

    public DocumentRecord {
        // Copie ordonnee et figee : l'ordre est celui de la configuration (A, puis B).
        indexes = indexes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(indexes));
    }

    public IndexState index(String name) {
        return indexes.get(name);
    }

    /** Statut d'ensemble, pour un affichage rapide : en cours si un index l'est, en echec si un index l'est. */
    @JsonProperty("status")
    public DocumentStatus status() {
        if (indexes.values().stream().anyMatch(s -> s.status() == DocumentStatus.PROCESSING)) {
            return DocumentStatus.PROCESSING;
        }
        if (indexes.values().stream().anyMatch(s -> s.status() == DocumentStatus.FAILED)) {
            return DocumentStatus.FAILED;
        }
        return indexes.isEmpty() ? DocumentStatus.PROCESSING : DocumentStatus.READY;
    }

    public boolean readyIn(String index) {
        IndexState state = indexes.get(index);
        return state != null && state.isReady();
    }

    public DocumentRecord withIndex(String name, IndexState state) {
        Map<String, IndexState> next = new LinkedHashMap<>(indexes);
        next.put(name, state);
        return new DocumentRecord(documentId, fileName, sourceType, collection, sha256, next, fileStored, submittedAt);
    }

    public DocumentRecord withCollection(String newCollection) {
        return new DocumentRecord(documentId, fileName, sourceType, newCollection, sha256, indexes, fileStored, submittedAt);
    }

    public DocumentRecord withFileStored(boolean stored) {
        return new DocumentRecord(documentId, fileName, sourceType, collection, sha256, indexes, stored, submittedAt);
    }
}

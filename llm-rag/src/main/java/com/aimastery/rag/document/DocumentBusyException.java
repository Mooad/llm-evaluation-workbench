package com.aimastery.rag.document;

public class DocumentBusyException extends RuntimeException {

    public DocumentBusyException(String documentId) {
        super("Document en cours d'indexation : " + documentId + ". Reessayer quand il est pret.");
    }
}

package com.aimastery.rag.document;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(String documentId) {
        super("Document inconnu : " + documentId);
    }
}

package com.aimastery.rag.ingestion.source;

import java.util.List;

public class UnsupportedDocumentException extends RuntimeException {

    public UnsupportedDocumentException(String fileName, List<String> supported) {
        super("Format non reconnu pour " + fileName + " (formats acceptes : " + String.join(", ", supported) + ")");
    }
}

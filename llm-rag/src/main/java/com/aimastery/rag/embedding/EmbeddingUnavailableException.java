package com.aimastery.rag.embedding;

public class EmbeddingUnavailableException extends RuntimeException {

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

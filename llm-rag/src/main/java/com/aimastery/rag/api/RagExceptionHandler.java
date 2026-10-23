package com.aimastery.rag.api;

import com.aimastery.rag.document.DocumentBusyException;
import com.aimastery.rag.document.DocumentNotFoundException;
import com.aimastery.rag.index.UnknownIndexException;
import com.aimastery.rag.embedding.EmbeddingUnavailableException;
import com.aimastery.rag.ingestion.InvalidDocumentException;
import com.aimastery.rag.ingestion.source.UnsupportedDocumentException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Meme forme d'erreur que le Gateway : {error, message, timestamp}. */
@RestControllerAdvice
public class RagExceptionHandler {

    @ExceptionHandler(InvalidDocumentException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidDocument(InvalidDocumentException e) {
        return build(HttpStatus.BAD_REQUEST, "invalid_document", e.getMessage());
    }

    @ExceptionHandler(UnsupportedDocumentException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupported(UnsupportedDocumentException e) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_document", e.getMessage());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPart(MissingServletRequestPartException e) {
        return build(HttpStatus.BAD_REQUEST, "invalid_document", "Champ multipart \"file\" absent");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(MaxUploadSizeExceededException e) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "document_too_large", e.getMessage());
    }

    @ExceptionHandler(DocumentBusyException.class)
    public ResponseEntity<Map<String, Object>> handleBusy(DocumentBusyException e) {
        return build(HttpStatus.CONFLICT, "document_busy", e.getMessage());
    }

    @ExceptionHandler(UnknownIndexException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownIndex(UnknownIndexException e) {
        return build(HttpStatus.BAD_REQUEST, "unknown_index", e.getMessage());
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(DocumentNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "document_not_found", e.getMessage());
    }

    /** La recherche vectorise la question : sans modele d'embedding, pas de recherche. */
    @ExceptionHandler(EmbeddingUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleEmbedding(EmbeddingUnavailableException e) {
        return build(HttpStatus.BAD_GATEWAY, "embedding_unavailable", e.getMessage());
    }

    /** Parametre de requete hors bornes (topK, scope) : erreur du client, pas du service. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException e) {
        return build(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, "invalid_request", message);
    }

    private static ResponseEntity<Map<String, Object>> build(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", error,
                "message", message == null ? "" : message,
                "timestamp", Instant.now().toString()));
    }
}

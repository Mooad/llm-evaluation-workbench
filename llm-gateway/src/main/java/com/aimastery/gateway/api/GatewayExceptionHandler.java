package com.aimastery.gateway.api;

import com.aimastery.gateway.core.AllProvidersFailedException;
import com.aimastery.gateway.core.ProviderCallException;
import com.aimastery.gateway.core.ProviderNotFoundException;
import com.aimastery.gateway.eval.DatasetAlreadyExistsException;
import com.aimastery.gateway.eval.EvalAlreadyRunningException;
import com.aimastery.gateway.eval.EvalDatasetException;
import com.aimastery.gateway.eval.EvalJobNotFoundException;
import com.aimastery.gateway.eval.ReportNotFoundException;
import com.aimastery.gateway.structured.InvalidSchemaException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GatewayExceptionHandler {

    @ExceptionHandler(ProviderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownProvider(ProviderNotFoundException e) {
        return build(HttpStatus.BAD_REQUEST, "unknown_provider", e.getMessage());
    }

    @ExceptionHandler(ProviderCallException.class)
    public ResponseEntity<Map<String, Object>> handleProviderFailure(ProviderCallException e) {
        return build(HttpStatus.BAD_GATEWAY, "provider_unavailable", e.getMessage());
    }

    @ExceptionHandler(AllProvidersFailedException.class)
    public ResponseEntity<Map<String, Object>> handleAllFailed(AllProvidersFailedException e) {
        return build(HttpStatus.BAD_GATEWAY, "all_providers_failed", e.getMessage());
    }

    @ExceptionHandler(InvalidSchemaException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidSchema(InvalidSchemaException e) {
        return build(HttpStatus.BAD_REQUEST, "invalid_schema", e.getMessage());
    }

    @ExceptionHandler(InvalidSettingsException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidSettings(InvalidSettingsException e) {
        return build(HttpStatus.BAD_REQUEST, "invalid_settings", e.getMessage());
    }

    @ExceptionHandler(ReportNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleReportNotFound(ReportNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "report_not_found", e.getMessage());
    }

    @ExceptionHandler(EvalJobNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleJobNotFound(EvalJobNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "run_not_found", e.getMessage());
    }

    @ExceptionHandler(EvalAlreadyRunningException.class)
    public ResponseEntity<Map<String, Object>> handleAlreadyRunning(EvalAlreadyRunningException e) {
        return build(HttpStatus.CONFLICT, "eval_running", e.getMessage());
    }

    @ExceptionHandler(DatasetAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleDatasetExists(DatasetAlreadyExistsException e) {
        return build(HttpStatus.CONFLICT, "dataset_exists", e.getMessage());
    }

    @ExceptionHandler(EvalDatasetException.class)
    public ResponseEntity<Map<String, Object>> handleDataset(EvalDatasetException e) {
        return build(HttpStatus.BAD_REQUEST, "invalid_dataset", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " : " + error.getDefaultMessage())
                .reduce((a, b) -> a + " ; " + b)
                .orElse("requete invalide");
        return build(HttpStatus.BAD_REQUEST, "invalid_request", details);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", code,
                "message", message,
                "timestamp", Instant.now().toString()));
    }
}

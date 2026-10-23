package com.aimastery.gateway.api;

import com.aimastery.gateway.api.dto.ChatRequest;
import com.aimastery.gateway.api.dto.MessageDto;
import com.aimastery.gateway.api.dto.StreamSummary;
import com.aimastery.gateway.core.AllProvidersFailedException;
import com.aimastery.gateway.core.ChatOptions;
import com.aimastery.gateway.core.ChatService;
import com.aimastery.gateway.core.Message;
import com.aimastery.gateway.core.ProviderCallException;
import com.aimastery.gateway.core.ProviderRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * POST /chat/stream — meme contrat d'entree que /chat, reponse en Server-Sent Events :
 *
 *   event: delta   data: {"content": "..."}      un par fragment de texte
 *   event: done    data: StreamSummary           tokens, cout, latences, fallback
 *   event: error   data: {"error", "message"}    fin anormale, le flux s'arrete
 *
 * POST et non GET : l'historique complet part dans le corps (Gateway sans memoire),
 * ce qu'EventSource ne sait pas faire. Le client lit le flux avec fetch.
 */
@RestController
public class ChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);

    /** Au-dela du timeout fournisseur le plus long (Ollama, 180 s). */
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);

    private final ChatService chatService;
    private final ProviderRegistry registry;
    private final TaskExecutor executor;

    public ChatStreamController(ChatService chatService, ProviderRegistry registry, TaskExecutor executor) {
        this.chatService = chatService;
        this.registry = registry;
        this.executor = executor;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        // Verifie avant d'ouvrir le flux : un fournisseur inconnu doit rester un 400 JSON,
        // pas un evenement d'erreur au milieu d'un 200.
        if (request.provider() != null && !request.provider().isBlank()) {
            registry.resolve(request.provider());
        }

        List<Message> messages = request.messages().stream().map(MessageDto::toDomain).toList();
        ChatOptions options = new ChatOptions(
                request.temperature(), request.topP(), request.maxTokens(), request.reasoning());
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());

        // Le flux tourne sur un autre thread : sans copie du MDC, ses lignes de cout
        // perdraient leur request_id.
        Map<String, String> context = MDC.getCopyOfContextMap();
        executor.execute(() -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                run(emitter, request, messages, options);
            } finally {
                MDC.clear();
            }
        });
        return emitter;
    }

    private void run(SseEmitter emitter, ChatRequest request, List<Message> messages, ChatOptions options) {
        long start = System.nanoTime();
        AtomicLong firstTokenMs = new AtomicLong(-1);
        try {
            ChatService.ChatOutcome outcome = chatService.stream(
                    request.provider(), request.model(), messages, options,
                    delta -> {
                        firstTokenMs.compareAndSet(-1, (System.nanoTime() - start) / 1_000_000);
                        send(emitter, "delta", Map.of("content", delta));
                    });
            Long ttft = firstTokenMs.get() < 0 ? null : firstTokenMs.get();
            send(emitter, "done", StreamSummary.of(outcome, ttft));
            emitter.complete();

        } catch (UncheckedIOException e) {
            // Le client a ferme la connexion : rien a lui envoyer.
            log.info("flux abandonne par le client : {}", e.getMessage());
            emitter.complete();
        } catch (AllProvidersFailedException e) {
            fail(emitter, "all_providers_failed", e.getMessage());
        } catch (ProviderCallException e) {
            fail(emitter, firstTokenMs.get() < 0 ? "provider_unavailable" : "stream_interrupted", e.getMessage());
        } catch (RuntimeException e) {
            log.error("flux en echec", e);
            fail(emitter, "internal_error", e.getMessage());
        }
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void fail(SseEmitter emitter, String code, String message) {
        try {
            send(emitter, "error", Map.of("error", code, "message", message == null ? "" : message));
            emitter.complete();
        } catch (UncheckedIOException e) {
            emitter.complete();
        }
    }
}

package com.aimastery.gateway.api;

import com.aimastery.gateway.api.dto.ChatRequest;
import com.aimastery.gateway.api.dto.ChatResponse;
import com.aimastery.gateway.api.dto.MessageDto;
import com.aimastery.gateway.api.dto.StructuredChatRequest;
import com.aimastery.gateway.api.dto.StructuredChatResponse;
import com.aimastery.gateway.structured.StructuredChatService;
import com.aimastery.gateway.core.ChatOptions;
import com.aimastery.gateway.core.ChatService;
import com.aimastery.gateway.core.Message;
import com.aimastery.gateway.core.ProviderHealth;
import com.aimastery.gateway.core.ProviderGuard;
import com.aimastery.gateway.core.ProviderHealthService;
import com.aimastery.gateway.core.ProviderRegistry;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final ChatService chatService;
    private final StructuredChatService structuredChatService;
    private final ProviderRegistry registry;
    private final ProviderHealthService healthService;
    private final ProviderGuard guard;

    public ChatController(ChatService chatService,
                          StructuredChatService structuredChatService,
                          ProviderRegistry registry,
                          ProviderHealthService healthService,
                          ProviderGuard guard) {
        this.chatService = chatService;
        this.structuredChatService = structuredChatService;
        this.registry = registry;
        this.healthService = healthService;
        this.guard = guard;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        List<Message> messages = request.messages().stream()
                .map(MessageDto::toDomain)
                .toList();

        ChatOptions options = new ChatOptions(
                request.temperature(), request.topP(), request.maxTokens(), request.reasoning());

        ChatService.ChatOutcome outcome = chatService.chat(
                request.provider(), request.model(), messages, options);

        return ResponseEntity.ok(new ChatResponse(
                outcome.result().content(),
                outcome.provider(),
                outcome.requestedProvider(),
                outcome.fallbackUsed(),
                outcome.result().model(),
                outcome.result().usage().inputTokens(),
                outcome.result().usage().outputTokens(),
                outcome.cost().amount(),
                outcome.cost().currency(),
                outcome.latencyMs(),
                outcome.result().finishReason(),
                outcome.result().providerFinishReason(),
                outcome.applied()));
    }

    @PostMapping("/chat/structured")
    public ResponseEntity<StructuredChatResponse> structured(
            @Valid @RequestBody StructuredChatRequest request) {

        List<Message> messages = request.messages().stream().map(MessageDto::toDomain).toList();
        ChatOptions options = new ChatOptions(
                request.temperature(), null, request.maxTokens(), null);

        StructuredChatService.StructuredResult result = structuredChatService.chat(
                request.provider(), request.model(), messages, request.schema(), options);

        ChatService.ChatOutcome outcome = result.outcome();

        StructuredChatResponse body = new StructuredChatResponse(
                result.validation().valid(),
                result.validation().data(),
                result.validation().violations(),
                result.validation().raw(),
                outcome.provider(),
                outcome.requestedProvider(),
                outcome.fallbackUsed(),
                outcome.result().model(),
                outcome.result().usage().inputTokens(),
                outcome.result().usage().outputTokens(),
                outcome.latencyMs());

        // 422 : le modele a repondu, mais pas au format demande. Ce n'est pas
        // une panne (5xx) ni une requete invalide (400) — c'est un echec de format.
        return result.validation().valid()
                ? ResponseEntity.ok(body)
                : ResponseEntity.unprocessableEntity().body(body);
    }

    @GetMapping("/providers")
    public Map<String, Object> providers() {
        return Map.of(
                "available", registry.available(),
                "default", registry.defaultProvider());
    }


    /**
     * Etat de chaque fournisseur : joignable, modele disponible, latence de la sonde.
     * Sondes legeres et paralleles — aucune generation, aucun token consomme.
     */
    @GetMapping("/providers/status")
    public ResponseEntity<Map<String, Object>> status() {
        List<ProviderHealth> health = healthService.checkAll();
        boolean anyUp = health.stream().anyMatch(h -> h.status() == ProviderHealth.Status.UP);

        Map<String, Object> body = Map.of(
                "overall", anyUp ? "UP" : "DOWN",
                "default", registry.defaultProvider(),
                "providers", health,
                // La sonde dit si le fournisseur repond maintenant ; le circuit, s'il est ecarte.
                "circuits", guard.circuits());

        // 503 quand plus aucun fournisseur ne peut servir : exploitable par une sonde externe.
        return anyUp ? ResponseEntity.ok(body)
                     : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}

package com.aimastery.gateway.structured;

import com.aimastery.gateway.core.ChatOptions;
import com.aimastery.gateway.core.ChatService;
import com.aimastery.gateway.core.Message;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Sortie structuree : on enrichit le prompt du schema, on appelle le Gateway
 * normalement, puis on valide. Le routage et le fallback restent inchanges.
 */
@Service
public class StructuredChatService {

    private final ChatService chatService;
    private final SchemaPromptBuilder promptBuilder;
    private final SchemaValidator validator;

    public StructuredChatService(ChatService chatService,
                                 SchemaPromptBuilder promptBuilder,
                                 SchemaValidator validator) {
        this.chatService = chatService;
        this.promptBuilder = promptBuilder;
        this.validator = validator;
    }

    public StructuredResult chat(String provider,
                                 String model,
                                 List<Message> messages,
                                 JsonNode schema,
                                 ChatOptions options) {

        // Avant l'appel : un schema refuse ne doit consommer aucun token.
        SchemaSupport.requireSupported(schema);

        // Temperature basse par defaut : une sortie machine n'a aucun besoin de variete.
        ChatOptions effective = (options.temperature() == null)
                ? new ChatOptions(0.0, options.topP(), options.maxTokens(), options.reasoning())
                : options;

        ChatService.ChatOutcome outcome = chatService.chat(
                provider, model, promptBuilder.build(messages, schema), effective);

        StructuredOutcome validation = validator.validate(outcome.result().content(), schema);

        return new StructuredResult(outcome, validation);
    }

    public record StructuredResult(ChatService.ChatOutcome outcome, StructuredOutcome validation) {
    }
}

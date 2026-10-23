package com.aimastery.gateway.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record StructuredChatRequest(
        String provider,
        String model,

        @NotEmpty(message = "messages ne peut pas etre vide")
        @Valid
        List<MessageDto> messages,

        @NotNull(message = "schema est obligatoire")
        JsonNode schema,

        Double temperature,

        @Positive
        Integer maxTokens) {
}

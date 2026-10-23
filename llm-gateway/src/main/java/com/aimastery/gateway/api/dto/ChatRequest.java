package com.aimastery.gateway.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * Le Gateway est sans memoire : le client renvoie l'historique complet a chaque appel.
 */
public record ChatRequest(
        String provider,
        String model,

        @NotEmpty(message = "messages ne peut pas etre vide")
        @Valid
        List<MessageDto> messages,

        @DecimalMin(value = "0.0") @DecimalMax(value = "2.0")
        Double temperature,

        @DecimalMin(value = "0.0") @DecimalMax(value = "1.0")
        Double topP,

        @Positive
        Integer maxTokens,

        /** Mode raisonnement (DeepSeek). null = defaut global, puis defaut du fournisseur. */
        Boolean reasoning) {
}

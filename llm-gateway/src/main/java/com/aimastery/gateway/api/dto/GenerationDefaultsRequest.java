package com.aimastery.gateway.api.dto;

import com.aimastery.gateway.core.GenerationDefaults;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;

/** Champs a null = ne rien imposer, le fournisseur garde son propre defaut. */
public record GenerationDefaultsRequest(
        Boolean reasoning,

        @Positive
        Integer maxTokens,

        @DecimalMin(value = "0.0") @DecimalMax(value = "2.0")
        Double temperature) {

    public GenerationDefaults toDomain() {
        return new GenerationDefaults(reasoning, maxTokens, temperature);
    }
}

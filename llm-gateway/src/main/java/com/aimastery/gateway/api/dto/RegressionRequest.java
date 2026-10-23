package com.aimastery.gateway.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

/**
 * @param thresholdPoints null = seuil de configuration (gateway.eval.regression-threshold-points)
 */
public record RegressionRequest(@NotBlank String baseline,
                                @NotBlank String candidate,
                                @DecimalMin("0.0") @DecimalMax("100.0") Double thresholdPoints) {
}

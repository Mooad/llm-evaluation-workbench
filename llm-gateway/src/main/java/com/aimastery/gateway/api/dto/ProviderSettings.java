package com.aimastery.gateway.api.dto;

import com.aimastery.gateway.core.GenerationDefaults;

/**
 * @param supportsReasoning le reglage "reasoning" n'a d'effet que si ce fournisseur le gere
 */
public record ProviderSettings(String provider, boolean supportsReasoning, GenerationDefaults defaults) {
}

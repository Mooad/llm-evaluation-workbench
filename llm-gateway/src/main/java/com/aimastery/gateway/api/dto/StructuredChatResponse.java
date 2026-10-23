package com.aimastery.gateway.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record StructuredChatResponse(boolean valid,
                                     JsonNode data,
                                     List<String> violations,
                                     String raw,
                                     String provider,
                                     String requestedProvider,
                                     boolean fallbackUsed,
                                     String model,
                                     Integer inputTokens,
                                     Integer outputTokens,
                                     long latencyMs) {
}

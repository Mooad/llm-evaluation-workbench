package com.aimastery.gateway.provider.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Reponse de POST /chat/completions, au format OpenAI. */
@JsonIgnoreProperties(ignoreUnknown = true)
record ChatCompletionResponse(String model, List<Choice> choices, WireUsage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Content message, @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Content(String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireUsage(@JsonProperty("prompt_tokens") Integer promptTokens,
                     @JsonProperty("completion_tokens") Integer completionTokens) {
    }
}

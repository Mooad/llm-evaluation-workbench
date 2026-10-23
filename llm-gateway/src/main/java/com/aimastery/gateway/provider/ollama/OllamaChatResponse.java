package com.aimastery.gateway.provider.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reponse de /api/chat. En flux, une ligne par fragment : seule la derniere
 * (done = true) porte les compteurs de tokens et la raison d'arret.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record OllamaChatResponse(String model,
                          OllamaMessage message,
                          boolean done,
                          String error,
                          @JsonProperty("done_reason") String doneReason,
                          @JsonProperty("prompt_eval_count") Integer promptEvalCount,
                          @JsonProperty("eval_count") Integer evalCount) {
}

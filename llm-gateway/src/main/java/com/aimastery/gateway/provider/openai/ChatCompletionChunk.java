package com.aimastery.gateway.provider.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Un evenement "data:" d'un flux /chat/completions. Le texte arrive dans delta.content ;
 * le dernier evenement (choices vide) porte l'usage. Le raisonnement (reasoning_content, DeepSeek)
 * n'est pas transmis : seule la reponse interesse le client.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ChatCompletionChunk(String model, List<Choice> choices, ChatCompletionResponse.WireUsage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Delta delta, @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Delta(String content) {
    }
}

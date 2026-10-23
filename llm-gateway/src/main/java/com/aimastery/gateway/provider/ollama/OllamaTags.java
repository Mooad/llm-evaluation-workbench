package com.aimastery.gateway.provider.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Reponse de GET /api/tags : les modeles installes localement. */
@JsonIgnoreProperties(ignoreUnknown = true)
record OllamaTags(List<Model> models) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Model(String name) {
    }
}

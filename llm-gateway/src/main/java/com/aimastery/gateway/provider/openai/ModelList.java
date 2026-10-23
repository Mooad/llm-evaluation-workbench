package com.aimastery.gateway.provider.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Reponse de GET /models : les modeles servis par l'API. */
@JsonIgnoreProperties(ignoreUnknown = true)
record ModelList(List<Model> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Model(String id) {
    }
}

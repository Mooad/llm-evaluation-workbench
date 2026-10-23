package com.aimastery.rag.chunk;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Ou se trouve un passage dans sa source : c'est ce qu'on cite a l'utilisateur.
 *
 * Une page pour un PDF, un intervalle de temps pour une video, au lieu d'un champ "page" qui
 * ne voudrait rien dire pour une video. Interface scellee : la liste des formes possibles est
 * fermee et visible ici, et chaque nouvelle forme se declare dans permits et JsonSubTypes.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PageLocation.class, name = PageLocation.TYPE),
        @JsonSubTypes.Type(value = PageRangeLocation.class, name = PageRangeLocation.TYPE),
        @JsonSubTypes.Type(value = TimeRangeLocation.class, name = TimeRangeLocation.TYPE)})
public sealed interface SourceLocation permits PageLocation, PageRangeLocation, TimeRangeLocation {

    /** Forme lisible, pour un log ou une citation : "page 365", "02:14-02:48". */
    String label();
}

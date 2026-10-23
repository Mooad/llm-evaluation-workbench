package com.aimastery.gateway.structured;

import com.aimastery.gateway.core.Message;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Injecte le schema dans le prompt.
 *
 * Aucun fournisseur local ne garantit le format : on demande, on ne contraint pas.
 * D'ou la validation systematique en sortie. Les APIs qui savent vraiment
 * contraindre le decodage rendront cette classe inutile — c'est le but.
 */
@Component
public class SchemaPromptBuilder {

    public List<Message> build(List<Message> messages, JsonNode schema) {
        String instruction = """
                Tu reponds UNIQUEMENT par un objet JSON valide, conforme au schema ci-dessous.
                Aucun texte avant, aucun texte apres, aucun bloc de code.

                SCHEMA :
                %s
                """.formatted(schema.toPrettyString());

        List<Message> enriched = new ArrayList<>();
        enriched.add(Message.system(instruction));
        enriched.addAll(messages);
        return enriched;
    }
}

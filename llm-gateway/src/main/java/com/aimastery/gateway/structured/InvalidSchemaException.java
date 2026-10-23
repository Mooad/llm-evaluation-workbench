package com.aimastery.gateway.structured;

import java.util.List;

/** Schema fourni par l'appelant que le Gateway ne sait pas verifier : erreur client (400). */
public class InvalidSchemaException extends RuntimeException {

    private final List<String> problems;

    public InvalidSchemaException(List<String> problems) {
        super("Schema non gere : " + String.join(" ; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}

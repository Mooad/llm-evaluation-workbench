package com.aimastery.rag.index;

import java.util.List;

public class UnknownIndexException extends RuntimeException {

    public UnknownIndexException(String name, List<String> known) {
        super("Index inconnu : " + name + " (index configures : " + String.join(", ", known) + ")");
    }
}

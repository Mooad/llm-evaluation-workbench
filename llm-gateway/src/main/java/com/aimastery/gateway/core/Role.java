package com.aimastery.gateway.core;

public enum Role {
    SYSTEM,
    USER,
    ASSISTANT;

    public String wireName() {
        return name().toLowerCase();
    }
}

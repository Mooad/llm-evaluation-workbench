package com.aimastery.gateway.core;

import java.util.List;

/**
 * Une demande normalisee, deja resolue : le fournisseur et le modele sont decides.
 */
public record ChatCall(String model, List<Message> messages, ChatOptions options) {
}

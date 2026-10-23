package com.aimastery.gateway.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Empreinte d'un cas et d'un dataset.
 *
 * Deux executions ne se comparent que sur les cas identiques des deux cotes : un cas dont
 * le prompt ou la reference a change ne mesure plus la meme chose. L'empreinte permet de
 * les ecarter, et de comparer sans risque deux versions d'un dataset qui a grossi.
 */
public final class CaseFingerprint {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int LENGTH = 12;

    private CaseFingerprint() {
    }

    public static String of(EvalCase c) {
        try {
            return sha256(MAPPER.writeValueAsString(c));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cas impossible a serialiser : " + c.id(), e);
        }
    }

    /** Change des qu'un cas est ajoute, retire, modifie ou deplace. */
    public static String ofDataset(List<EvalCase> cases) {
        StringBuilder all = new StringBuilder();
        cases.forEach(c -> all.append(of(c)));
        return sha256(all.toString());
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.aimastery.rag.ingestion.source;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Un fichier recu, avant toute interpretation : un nom et des octets. */
public record SourceFile(String fileName, byte[] content) {

    /** Detection par signature ("magic bytes") : le nom et le Content-Type sont choisis par le client. */
    public boolean startsWith(String signature) {
        byte[] prefix = signature.getBytes(StandardCharsets.ISO_8859_1);
        return content.length >= prefix.length && Arrays.equals(content, 0, prefix.length, prefix, 0, prefix.length);
    }
}

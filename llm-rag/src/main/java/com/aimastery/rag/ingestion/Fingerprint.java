package com.aimastery.rag.ingestion;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Empreinte SHA-256 du fichier : deux imports du meme contenu donnent le meme document,
 * quel que soit le nom du fichier.
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 absent de la JVM", e);
        }
    }

    /**
     * L'identifiant est tire de l'empreinte, pas d'un compteur : apres un redemarrage (registre
     * en memoire perdu), reimporter le fichier retombe sur les memes points Qdrant.
     * 16 caracteres hexa (64 bits) : assez pour ne pas entrer en collision, assez court pour un curl.
     */
    public static String documentId(String sha256) {
        return sha256.substring(0, 16);
    }
}

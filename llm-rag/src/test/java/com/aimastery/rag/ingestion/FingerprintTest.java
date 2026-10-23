package com.aimastery.rag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FingerprintTest {

    @Test
    void calcule_le_sha256_standard() {
        // Vecteur de test officiel (FIPS 180-2).
        assertThat(Fingerprint.sha256("abc".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void le_meme_contenu_donne_le_meme_identifiant_de_document() {
        byte[] content = "%PDF-1.7 contenu".getBytes(StandardCharsets.US_ASCII);

        assertThat(Fingerprint.documentId(Fingerprint.sha256(content)))
                .isEqualTo(Fingerprint.documentId(Fingerprint.sha256(content.clone())))
                .hasSize(16);
    }

    @Test
    void un_octet_de_difference_change_l_identifiant() {
        String a = Fingerprint.documentId(Fingerprint.sha256("%PDF-1.7 A".getBytes(StandardCharsets.US_ASCII)));
        String b = Fingerprint.documentId(Fingerprint.sha256("%PDF-1.7 B".getBytes(StandardCharsets.US_ASCII)));

        assertThat(a).isNotEqualTo(b);
    }
}

package com.aimastery.rag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentRegistryTest {

    private static final Instant T0 = Instant.parse("2026-10-16T10:00:00Z");
    private static final List<String> INDEXES = List.of("A", "B");

    @TempDir
    Path dir;

    private DocumentRegistry registry(Path file) {
        return new DocumentRegistry(file, "A");
    }

    private static DocumentRecord candidate() {
        return new DocumentRecord("1238f494822a53b8", "notice.pdf", "pdf", null, "sha", Map.of(), true, T0);
    }

    @Test
    void un_nouveau_document_est_a_indexer_dans_tous_les_index() {
        DocumentRegistry.Claim claim = registry(dir.resolve("documents.json")).claim(candidate(), INDEXES, false);

        assertThat(claim.toIndex()).containsExactly("A", "B");
        assertThat(claim.record().status()).isEqualTo(DocumentStatus.PROCESSING);
    }

    @Test
    void seul_l_index_manquant_ou_en_echec_est_refait() {
        DocumentRegistry registry = registry(dir.resolve("documents.json"));
        registry.claim(candidate(), INDEXES, false);
        registry.updateIndex("1238f494822a53b8", "A", IndexState.ready(469, 780, T0));
        registry.updateIndex("1238f494822a53b8", "B", IndexState.failed("Ollama arrete", T0));

        assertThat(registry.claim(candidate(), INDEXES, false).toIndex()).containsExactly("B");
    }

    @Test
    void un_document_pret_partout_n_est_pas_reindexe_sauf_force_et_jamais_en_cours() {
        DocumentRegistry registry = registry(dir.resolve("documents.json"));
        registry.claim(candidate(), INDEXES, false);
        registry.updateIndex("1238f494822a53b8", "A", IndexState.ready(469, 780, T0));

        // B est en cours : ni un import normal ni un import force ne le relancent.
        assertThat(registry.claim(candidate(), INDEXES, false).created()).isFalse();
        assertThat(registry.claim(candidate(), INDEXES, true).toIndex()).containsExactly("A");
    }

    @Test
    void l_etat_par_index_survit_au_redemarrage() {
        Path file = dir.resolve("state/documents.json");
        DocumentRegistry before = registry(file);
        before.claim(candidate(), INDEXES, false);
        before.updateIndex("1238f494822a53b8", "A", IndexState.ready(469, 780, T0));
        before.updateIndex("1238f494822a53b8", "B", IndexState.ready(469, 612, T0));

        DocumentRecord after = registry(file).find("1238f494822a53b8").orElseThrow();

        assertThat(after.status()).isEqualTo(DocumentStatus.READY);
        assertThat(after.index("A").chunks()).isEqualTo(780);
        assertThat(after.index("B").chunks()).isEqualTo(612);
        assertThat(after.indexes().keySet()).containsExactly("A", "B");
        assertThat(after.fileStored()).isTrue();
    }

    @Test
    void une_indexation_coupee_par_un_redemarrage_repasse_en_echec() {
        Path file = dir.resolve("documents.json");
        registry(file).claim(candidate(), INDEXES, false);

        DocumentRecord after = registry(file).find("1238f494822a53b8").orElseThrow();

        assertThat(after.index("A").status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(after.index("A").error()).isEqualTo(DocumentRegistry.INTERRUPTED);
    }

    @Test
    void un_registre_d_avant_les_index_est_rattache_au_premier_index_sans_fichier_conserve() throws Exception {
        Path file = dir.resolve("documents.json");
        Files.writeString(file, """
                [ { "documentId" : "1238f494822a53b8", "fileName" : "notice.pdf", "sha256" : "sha",
                    "status" : "READY", "pages" : 472, "chunks" : 780, "error" : null,
                    "submittedAt" : "2026-10-16T20:10:17Z", "completedAt" : "2026-10-16T20:11:05Z" } ]
                """);

        DocumentRecord migrated = registry(file).find("1238f494822a53b8").orElseThrow();

        assertThat(migrated.index("A").status()).isEqualTo(DocumentStatus.READY);
        assertThat(migrated.index("A").sections()).isEqualTo(472);
        assertThat(migrated.index("A").chunks()).isEqualTo(780);
        assertThat(migrated.index("B")).isNull();
        assertThat(migrated.fileStored()).isFalse();
        // Le fichier est reecrit au nouveau format : la migration ne se refait pas a chaque demarrage.
        assertThat(Files.readString(file)).contains("\"indexes\"");
    }

    @Test
    void un_document_en_cours_ne_peut_pas_etre_supprime() {
        DocumentRegistry registry = registry(dir.resolve("documents.json"));
        registry.claim(candidate(), INDEXES, false);

        assertThatThrownBy(() -> registry.remove("1238f494822a53b8")).isInstanceOf(DocumentBusyException.class);

        registry.updateIndex("1238f494822a53b8", "A", IndexState.ready(1, 1, T0));
        registry.updateIndex("1238f494822a53b8", "B", IndexState.ready(1, 1, T0));
        assertThat(registry.remove("1238f494822a53b8")).isPresent();
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void un_fichier_illisible_bloque_le_demarrage_au_lieu_d_effacer_le_catalogue() throws Exception {
        Path file = dir.resolve("documents.json");
        Files.writeString(file, "{ pas du json");

        assertThatThrownBy(() -> registry(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Registre illisible");
        assertThat(Files.readString(file)).isEqualTo("{ pas du json");
    }

    @Test
    void sans_fichier_le_registre_demarre_vide() {
        assertThat(registry(dir.resolve("absent.json")).all()).isEmpty();
    }
}

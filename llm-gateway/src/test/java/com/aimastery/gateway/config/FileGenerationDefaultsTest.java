package com.aimastery.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aimastery.gateway.core.GenerationDefaults;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileGenerationDefaultsTest {

    @TempDir
    Path dir;

    private FileGenerationDefaults store(Path file) {
        return new FileGenerationDefaults(new SettingsProperties(file.toString()));
    }

    @Test
    void sans_fichier_aucun_fournisseur_n_a_de_defaut_impose() {
        assertThat(store(dir.resolve("absent.json")).forProvider("deepseek")).isEqualTo(GenerationDefaults.NONE);
    }

    @Test
    void une_modification_survit_au_redemarrage() {
        Path file = dir.resolve("settings/defaults.json");
        store(file).update("deepseek", new GenerationDefaults(false, 2048, null));

        // Nouvelle instance = redemarrage : la valeur doit venir du fichier.
        assertThat(store(file).forProvider("deepseek")).isEqualTo(new GenerationDefaults(false, 2048, null));
    }

    @Test
    void modifier_un_fournisseur_ne_touche_pas_aux_autres() {
        Path file = dir.resolve("defaults.json");
        FileGenerationDefaults store = store(file);
        store.update("ollama", new GenerationDefaults(null, 1024, 0.2));

        store.update("deepseek", new GenerationDefaults(false, null, null));

        assertThat(store(file).forProvider("ollama")).isEqualTo(new GenerationDefaults(null, 1024, 0.2));
    }

    @Test
    void un_fichier_illisible_bloque_le_demarrage() throws Exception {
        Path file = dir.resolve("defaults.json");
        Files.writeString(file, "{ pas du json");

        assertThatThrownBy(() -> store(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("illisible");
    }
}

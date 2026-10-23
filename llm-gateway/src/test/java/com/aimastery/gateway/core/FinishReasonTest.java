package com.aimastery.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FinishReasonTest {

    @ParameterizedTest
    @CsvSource({
            // Ollama, Anthropic et compatibles OpenAI aboutissent au meme verdict.
            "stop,          COMPLETE",
            "end_turn,      COMPLETE",
            "stop_sequence, COMPLETE",
            "length,        TRUNCATED",
            "max_tokens,    TRUNCATED",
            "refusal,       REFUSED",
            "content_filter,REFUSED",
            "tool_use,      TOOL_USE",
            "tool_calls,    TOOL_USE"
    })
    void traduit_le_vocabulaire_de_chaque_fournisseur(String raw, FinishReason expected) {
        assertThat(FinishReason.fromProvider(raw)).isEqualTo(expected);
    }

    @Test
    void trois_fournisseurs_un_seul_verdict_pour_une_reponse_terminee() {
        assertThat(FinishReason.fromProvider("stop"))       // ollama
                .isEqualTo(FinishReason.fromProvider("end_turn"))   // anthropic
                .isEqualTo(FinishReason.COMPLETE);
    }

    @Test
    void trois_fournisseurs_un_seul_verdict_pour_une_reponse_coupee() {
        assertThat(FinishReason.fromProvider("length"))     // qwen / openai
                .isEqualTo(FinishReason.fromProvider("max_tokens")) // anthropic
                .isEqualTo(FinishReason.TRUNCATED);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "quelque_chose_de_nouveau"})
    void retombe_sur_UNKNOWN_plutot_que_de_planter(String raw) {
        assertThat(FinishReason.fromProvider(raw)).isEqualTo(FinishReason.UNKNOWN);
    }

    @Test
    void est_insensible_a_la_casse() {
        assertThat(FinishReason.fromProvider("END_TURN")).isEqualTo(FinishReason.COMPLETE);
    }

    @Test
    void signale_les_reponses_sur_lesquelles_on_ne_doit_pas_batir() {
        assertThat(FinishReason.TRUNCATED.isIncomplete()).isTrue();
        assertThat(FinishReason.REFUSED.isIncomplete()).isTrue();
        assertThat(FinishReason.COMPLETE.isIncomplete()).isFalse();
    }

    @Test
    void conserve_le_libelle_brut_du_fournisseur_pour_le_diagnostic() {
        ChatResult result = ChatResult.of("bonjour", "un-modele", new Usage(1, 2), "end_turn");

        assertThat(result.finishReason()).isEqualTo(FinishReason.COMPLETE);
        assertThat(result.providerFinishReason()).isEqualTo("end_turn");
    }
}

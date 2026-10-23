package com.aimastery.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.gateway.core.AppliedOptions.Origin;
import org.junit.jupiter.api.Test;

class AppliedOptionsTest {

    private static final GenerationDefaults GLOBAL = new GenerationDefaults(false, 2048, null);

    @Test
    void la_requete_prime_sur_le_defaut_global() {
        AppliedOptions applied = AppliedOptions.resolve(new ChatOptions(null, null, 512, true), GLOBAL);

        assertThat(applied.maxTokens()).isEqualTo(new AppliedOptions.Setting<>(512, Origin.REQUEST));
        assertThat(applied.reasoning()).isEqualTo(new AppliedOptions.Setting<>(true, Origin.REQUEST));
    }

    @Test
    void le_defaut_global_complete_ce_que_la_requete_ne_precise_pas() {
        AppliedOptions applied = AppliedOptions.resolve(ChatOptions.defaults(), GLOBAL);

        assertThat(applied.reasoning()).isEqualTo(new AppliedOptions.Setting<>(false, Origin.GLOBAL));
        assertThat(applied.maxTokens()).isEqualTo(new AppliedOptions.Setting<>(2048, Origin.GLOBAL));
        assertThat(applied.toChatOptions()).isEqualTo(new ChatOptions(null, null, 2048, false));
    }

    @Test
    void sans_requete_ni_global_le_fournisseur_garde_son_propre_defaut() {
        AppliedOptions applied = AppliedOptions.resolve(ChatOptions.defaults(), GLOBAL);

        assertThat(applied.temperature()).isEqualTo(new AppliedOptions.Setting<>(null, Origin.PROVIDER));
    }
}

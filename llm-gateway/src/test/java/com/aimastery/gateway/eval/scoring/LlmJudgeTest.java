package com.aimastery.gateway.eval.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.gateway.config.GatewayProperties;
import com.aimastery.gateway.core.ChatCall;
import com.aimastery.gateway.core.ChatResult;
import com.aimastery.gateway.core.ChatService;
import com.aimastery.gateway.core.LlmProvider;
import com.aimastery.gateway.core.ProviderRegistry;
import com.aimastery.gateway.core.Usage;
import com.aimastery.gateway.eval.DimensionScore;
import com.aimastery.gateway.eval.Verdict;
import com.aimastery.gateway.observability.CallLogger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmJudgeTest {

    /** Juge scripte : renvoie une reponse fixe et garde le dernier appel. */
    private static final class ScriptedJudge implements LlmProvider {
        private final ChatResult reply;
        private ChatCall lastCall;

        ScriptedJudge(ChatResult reply) {
            this.reply = reply;
        }

        @Override public String name() { return "juge"; }
        @Override public String defaultModel() { return "juge-model"; }
        @Override public ChatResult chat(ChatCall call) {
            this.lastCall = call;
            return reply;
        }
    }

    private static LlmJudge judgeBackedBy(LlmProvider provider) {
        GatewayProperties properties = new GatewayProperties(provider.name(), Map.of(
                provider.name(), new GatewayProperties.ProviderConfig("http://localhost", "juge-model", "", 5)));
        return new LlmJudge(new ChatService(new ProviderRegistry(List.of(provider), properties), new CallLogger()));
    }

    @Test
    void le_juge_coupe_le_raisonnement_pour_ne_pas_vider_son_budget() {
        ScriptedJudge provider = new ScriptedJudge(
                ChatResult.of("{\"pass\": true, \"reason\": \"ok\"}", "juge-model", new Usage(10, 8), "stop"));

        DimensionScore score = judgeBackedBy(provider).language("Bonjour", "fr", "juge", null);

        assertThat(score.verdict()).isEqualTo(Verdict.PASS);
        assertThat(provider.lastCall.options().reasoning()).isFalse();
        assertThat(provider.lastCall.options().temperature()).isZero();
    }

    @Test
    void une_reponse_vide_dit_pourquoi_au_lieu_de_pas_de_json() {
        ScriptedJudge provider = new ScriptedJudge(
                ChatResult.of("", "juge-model", new Usage(120, 300), "length"));

        DimensionScore score = judgeBackedBy(provider).language("Bonjour", "fr", "juge", null);

        assertThat(score.verdict()).isEqualTo(Verdict.ERROR);
        assertThat(score.reason()).contains("reponse vide").contains("TRUNCATED").contains("300");
    }

    @Test
    void la_consigne_de_langue_tolere_les_sigles_techniques() {
        ScriptedJudge provider = new ScriptedJudge(
                ChatResult.of("{\"pass\": true, \"reason\": \"ok\"}", "juge-model", new Usage(1, 1), "stop"));

        judgeBackedBy(provider).language("ملف SRT هو ملف نصي", "ar", "juge", null);

        String prompt = provider.lastCall.messages().get(provider.lastCall.messages().size() - 1).content();
        assertThat(prompt).contains("Arabic").contains("acronyms");
    }
}

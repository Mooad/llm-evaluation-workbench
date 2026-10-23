package com.aimastery.gateway.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.aimastery.gateway.config.GatewayProperties;
import com.aimastery.gateway.config.PricingProperties;
import com.aimastery.gateway.core.ChatCall;
import com.aimastery.gateway.core.ChatResult;
import com.aimastery.gateway.core.ChatService;
import com.aimastery.gateway.core.FallbackManager;
import com.aimastery.gateway.core.LlmProvider;
import com.aimastery.gateway.core.Message;
import com.aimastery.gateway.core.ProviderCallException;
import com.aimastery.gateway.core.ProviderRegistry;
import com.aimastery.gateway.core.Usage;
import com.aimastery.gateway.eval.scoring.LlmJudge;
import com.aimastery.gateway.eval.scoring.RuleScorer;
import com.aimastery.gateway.observability.CallContext;
import com.aimastery.gateway.observability.CallLogger;
import com.aimastery.gateway.structured.SchemaPromptBuilder;
import com.aimastery.gateway.structured.SchemaValidator;
import com.aimastery.gateway.structured.StructuredChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.core.io.DefaultResourceLoader;

class EvalRunnerTest {

    @TempDir
    Path reports;

    /** Repond de facon deterministe selon le prompt — sans modele. */
    private static final class ScriptedProvider implements LlmProvider {
        @Override public String name() { return "scripted"; }
        @Override public String defaultModel() { return "scripted-model"; }
        @Override public ChatResult chat(ChatCall call) {
            String prompt = call.messages().get(call.messages().size() - 1).content();
            String answer;
            if (prompt.contains("capital of Morocco")) answer = "Rabat";
            else if (prompt.contains("120 requests")) answer = "1800";
            else if (prompt.contains("name=Sarah")) answer = "{\"name\":\"Sarah\",\"age\":29,\"city\":\"Paris\"}";
            else if (prompt.contains("Ahmed joined")) answer = "{\"name\":\"Ahmed\",\"company\":\"NovaTech\",\"role\":\"backend\"}";
            else if (prompt.contains("1000 requests")) answer = "The answer is 950.";
            else if (prompt.contains("47 ont reussi")) answer = "{\"successRate\": 90, \"failures\": 5}";
            else answer = "Je ne sais pas.";
            return ChatResult.of(answer, "scripted-model", new Usage(10, 5), "stop");
        }
    }

    private static final class BrokenProvider implements LlmProvider {
        @Override public String name() { return "broken"; }
        @Override public String defaultModel() { return "broken-model"; }
        @Override public ChatResult chat(ChatCall call) {
            throw new ProviderCallException("broken", "panne simulee", null);
        }
    }

    private EvalRunner runner(LlmProvider... providers) {
        Map<String, GatewayProperties.ProviderConfig> configs = new LinkedHashMap<>();
        for (LlmProvider p : providers) {
            configs.put(p.name(), new GatewayProperties.ProviderConfig("http://localhost", p.defaultModel(), "", 5));
        }
        GatewayProperties properties = new GatewayProperties(providers[0].name(), configs);
        ProviderRegistry registry = new ProviderRegistry(List.of(providers), properties);
        ChatService chat = new ChatService(registry, new CallLogger(), new FallbackManager(registry, properties));
        ObjectMapper mapper = new ObjectMapper();
        return new EvalRunner(chat,
                new StructuredChatService(chat, new SchemaPromptBuilder(), new SchemaValidator()),
                new EvalDatasetLoader(new DefaultResourceLoader(), mapper),
                new RuleScorer(), new LlmJudge(chat),
                new EvalProperties("classpath:evaluation/dataset.json", reports.toString(), null, null, null, null, null),
                mapper, registry, new PricingProperties("USD", Map.of()));
    }

    private static EvalRun withoutJudge(String provider) {
        return new EvalRun(provider, null, null, null, false, null, null, 0.0, 512, false);
    }

    private static CaseResult byId(EvalReport report, String id) {
        return report.cases().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void note_chaque_cas_par_regle_et_ecrit_le_rapport() throws Exception {
        EvalReport report = runner(new ScriptedProvider()).run(withoutJudge("scripted"));

        assertThat(report.caseCount()).isEqualTo(35);
        assertThat(byId(report, "fact_001").verdict(Dimension.CORRECTNESS)).isEqualTo(Verdict.PASS);
        assertThat(byId(report, "math_001").verdict(Dimension.FORMAT)).isEqualTo(Verdict.PASS);
        assertThat(byId(report, "extract_001").verdict(Dimension.CORRECTNESS)).isEqualTo(Verdict.PASS);
        assertThat(byId(report, "extract_001").verdict(Dimension.FORMAT)).isEqualTo(Verdict.FAIL);
        assertThat(byId(report, "reasoning_002").verdict(Dimension.CORRECTNESS)).isEqualTo(Verdict.PASS);
        assertThat(byId(report, "reasoning_002").verdict(Dimension.FORMAT)).isEqualTo(Verdict.FAIL);

        // juge desactive : ce qui demande du jugement n'est pas note, et le dit
        assertThat(byId(report, "hallucination_001").verdict(Dimension.HALLUCINATION)).isEqualTo(Verdict.SKIPPED);

        assertThat(report.reportFile()).isNotNull();
        assertThat(Files.exists(Path.of(report.reportFile()))).isTrue();
    }

    @Test
    void une_sortie_conforme_mais_fausse_passe_le_format_et_echoue_la_correctness() {
        EvalReport report = runner(new ScriptedProvider()).run(withoutJudge("scripted"));

        // {"successRate": 90, "failures": 5} respecte le schema, mais 47/50 = 94 %.
        CaseResult rate = byId(report, "schema_004");
        assertThat(rate.schemaValid()).isTrue();
        assertThat(rate.verdict(Dimension.FORMAT)).isEqualTo(Verdict.PASS);
        assertThat(rate.scores()).filteredOn(s -> s.dimension() == Dimension.FORMAT)
                .extracting(DimensionScore::method).containsExactly("schema");
        assertThat(rate.verdict(Dimension.CORRECTNESS)).isEqualTo(Verdict.FAIL);
    }

    @Test
    void le_rapport_donne_le_taux_de_conformite_au_schema_par_fournisseur() {
        EvalReport report = runner(new ScriptedProvider()).run(withoutJudge("scripted"));

        // 5 cas a schema, seul schema_004 recoit du JSON ; les autres "Je ne sais pas."
        assertThat(report.schemaConformity()).containsOnlyKeys("scripted");
        EvalReport.SchemaStats stats = report.schemaConformity().get("scripted");
        assertThat(stats.cases()).isEqualTo(5);
        assertThat(stats.conform()).isEqualTo(1);
        assertThat(stats.rate()).isEqualTo(0.2);
        assertThat(byId(report, "schema_001").verdict(Dimension.FORMAT)).isEqualTo(Verdict.FAIL);
    }

    @Test
    void le_taux_ignore_les_cas_non_notes() {
        EvalReport report = runner(new ScriptedProvider()).run(withoutJudge("scripted"));
        EvalReport.DimensionStats format = report.dimensions().get(Dimension.FORMAT);

        assertThat(format.passRate()).isEqualTo(
                Math.round(1000.0 * format.passed() / (format.passed() + format.failed())) / 1000.0);
    }

    @Test
    void un_cas_servi_par_le_fallback_est_exclu_de_la_mesure() {
        EvalReport report = runner(new BrokenProvider(), new ScriptedProvider()).run(withoutJudge("broken"));

        CaseResult fact = byId(report, "fact_001");
        assertThat(fact.fallbackUsed()).isTrue();
        assertThat(fact.servedBy()).isEqualTo("scripted");
        assertThat(fact.verdict(Dimension.CORRECTNESS)).isEqualTo(Verdict.ERROR);
        assertThat(report.warnings()).anyMatch(w -> w.contains("repli"));
    }

    @Test
    void le_candidat_et_le_juge_sont_etiquetes_pour_separer_leur_cout() {
        SourceRecordingProvider provider = new SourceRecordingProvider();
        EvalRun run = new EvalRun("recording", null, null, List.of("hallucination_001"),
                true, null, null, 0.0, 512, false);

        runner(provider).run(run);

        assertThat(provider.sources.get(0)).isEqualTo(CallContext.SOURCE_EVAL);
        assertThat(provider.sources.subList(1, provider.sources.size()))
                .isNotEmpty().containsOnly(CallContext.SOURCE_EVAL_JUDGE);
        assertThat(MDC.get(CallContext.SOURCE)).isNull();
    }

    /** Note la source de chaque appel ; repond comme un juge satisfait. */
    private static final class SourceRecordingProvider implements LlmProvider {
        private final List<String> sources = new ArrayList<>();
        @Override public String name() { return "recording"; }
        @Override public String defaultModel() { return "recording-model"; }
        @Override public ChatResult chat(ChatCall call) {
            sources.add(MDC.get(CallContext.SOURCE));
            return ChatResult.of("{\"pass\": true, \"reason\": \"ok\"}", "recording-model", new Usage(1, 1), "stop");
        }
    }

    @Test
    void le_prompt_systeme_part_en_tete_de_chaque_cas_et_reste_dans_le_rapport() {
        List<List<Message>> received = new ArrayList<>();
        LlmProvider recording = new LlmProvider() {
            @Override public String name() { return "scripted"; }
            @Override public String defaultModel() { return "scripted-model"; }
            @Override public ChatResult chat(ChatCall call) {
                received.add(call.messages());
                return ChatResult.of("Rabat", "scripted-model", new Usage(1, 1), "stop");
            }
        };
        EvalRun run = new EvalRun("scripted", null, null, List.of("fact_001", "math_001"),
                false, null, null, 0.0, 512, false, "Si tu n'es pas sur, dis que tu ne sais pas.");

        EvalReport report = runner(recording).run(run);

        assertThat(received).hasSize(2).allSatisfy(messages -> {
            assertThat(messages.get(0)).isEqualTo(Message.system("Si tu n'es pas sur, dis que tu ne sais pas."));
            assertThat(messages.get(1).role()).isEqualTo(com.aimastery.gateway.core.Role.USER);
        });
        // Sans lui dans le rapport, une comparaison avant/apres ne saurait pas ce qui a change.
        assertThat(report.settings().systemPrompt()).isEqualTo("Si tu n'es pas sur, dis que tu ne sais pas.");
    }

    @Test
    void peut_filtrer_quelques_cas() {
        EvalRun run = new EvalRun("scripted", null, null, List.of("fact_001", "math_001"),
                false, null, null, 0.0, 512, false);

        assertThat(runner(new ScriptedProvider()).run(run).caseCount()).isEqualTo(2);
    }
}

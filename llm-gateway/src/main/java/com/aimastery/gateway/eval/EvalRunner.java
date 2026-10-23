package com.aimastery.gateway.eval;

import com.aimastery.gateway.core.ChatOptions;
import com.aimastery.gateway.core.ChatService;
import com.aimastery.gateway.core.FinishReason;
import com.aimastery.gateway.core.Message;
import com.aimastery.gateway.core.ProviderRegistry;
import com.aimastery.gateway.eval.scoring.FormatOutcome;
import com.aimastery.gateway.eval.scoring.LlmJudge;
import com.aimastery.gateway.eval.scoring.RuleScorer;
import com.aimastery.gateway.observability.CallContext;
import com.aimastery.gateway.structured.StructuredChatService;
import com.aimastery.gateway.structured.StructuredOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Rejoue le golden dataset contre un fournisseur et note chaque reponse sur les
 * dimensions demandees par le dataset.
 *
 * Ordre de preference pour noter : regle deterministe d'abord, juge LLM seulement
 * quand aucune regle ne sait trancher.
 */
@Service
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final ChatService chatService;
    private final StructuredChatService structuredChatService;
    private final EvalDatasetLoader loader;
    private final RuleScorer rules;
    private final LlmJudge judge;
    private final EvalProperties properties;
    private final ObjectMapper mapper;
    private final ProviderRegistry registry;
    private final com.aimastery.gateway.config.PricingProperties pricing;

    public EvalRunner(ChatService chatService,
                      StructuredChatService structuredChatService,
                      EvalDatasetLoader loader,
                      RuleScorer rules,
                      LlmJudge judge,
                      EvalProperties properties,
                      ObjectMapper mapper,
                      ProviderRegistry registry,
                      com.aimastery.gateway.config.PricingProperties pricing) {
        this.chatService = chatService;
        this.structuredChatService = structuredChatService;
        this.loader = loader;
        this.rules = rules;
        this.judge = judge;
        this.properties = properties;
        this.mapper = mapper;
        this.registry = registry;
        this.pricing = pricing;
    }

    public List<EvalCase> dataset(String path) {
        return loader.load(firstNonBlank(path, properties.datasetPath()));
    }

    public EvalReport run(EvalRun run) {
        return run(run, EvalProgress.NONE);
    }

    /**
     * Ce qui ferait echouer l'execution, verifie avant de la lancer en arriere-plan : un
     * dataset invalide ou un fournisseur inconnu doit etre un 400, pas un echec differe.
     *
     * @return le nombre de cas qui seront evalues
     */
    public int preflight(EvalRun run) {
        String datasetPath = firstNonBlank(run.datasetPath(), properties.datasetPath());
        List<EvalCase> cases = select(loader.load(datasetPath), run.caseIds());
        String candidate = firstNonBlank(run.provider(), registry.defaultProvider());
        registry.resolve(candidate);
        if (run.useJudge()) {
            registry.resolve(firstNonBlank(run.judgeProvider(), properties.judgeProvider(), candidate));
        }
        return cases.size();
    }

    public EvalReport run(EvalRun run, EvalProgress progress) {
        Instant started = Instant.now();
        String datasetPath = firstNonBlank(run.datasetPath(), properties.datasetPath());
        List<EvalCase> cases = select(loader.load(datasetPath), run.caseIds());
        progress.started(cases.size());

        String candidateProvider = firstNonBlank(run.provider(), registry.defaultProvider());
        String candidateModel = firstNonBlank(run.model(), registry.resolve(candidateProvider).defaultModel());
        String judgeProvider = firstNonBlank(run.judgeProvider(), properties.judgeProvider(), candidateProvider);
        String judgeModel = firstNonBlank(run.judgeModel(), properties.judgeModel(),
                registry.resolve(judgeProvider).defaultModel());

        log.info("eval start dataset={} cases={} candidate={}/{} judge={}",
                datasetPath, cases.size(), candidateProvider, candidateModel,
                run.useJudge() ? judgeProvider + "/" + judgeModel : "off");

        List<CaseResult> results = new ArrayList<>();
        for (EvalCase c : cases) {
            if (progress.cancelled()) {
                throw new EvalCancelledException(results.size(), cases.size());
            }
            CaseResult result = evaluate(c, run, candidateProvider, candidateModel, judgeProvider, judgeModel);
            log.info("eval case={} correctness={} format={} hallucination={} latency_ms={}",
                    c.id(), result.verdict(Dimension.CORRECTNESS), result.verdict(Dimension.FORMAT),
                    result.verdict(Dimension.HALLUCINATION), result.latencyMs());
            results.add(result);
            progress.caseDone(c.id(), results.size());
        }

        List<String> warnings = warnings(run, results, candidateProvider, candidateModel, judgeProvider, judgeModel);
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Path file = Path.of(properties.reportsDir(),
                "eval-" + FILE_TIMESTAMP.format(started) + "-" + sanitize(candidateProvider) + "-" + runId + ".json");

        RunContext context = new RunContext(runId, started, candidateProvider + "/" + candidateModel,
                run.useJudge() ? judgeProvider + "/" + judgeModel : "desactive",
                datasetPath, CaseFingerprint.ofDataset(cases),
                new EvalReport.RunSettings(run.temperature(), run.maxTokens(), run.useJudge(),
                        run.allowFallback(), run.caseIds() != null && !run.caseIds().isEmpty(),
                        run.systemPrompt() == null || run.systemPrompt().isBlank() ? null : run.systemPrompt()));
        EvalReport report = buildReport(context, results, warnings, file.toAbsolutePath().toString());

        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
        } catch (Exception e) {
            warnings.add("Rapport non ecrit sur disque : " + e.getMessage());
            report = buildReport(context, results, warnings, null);
        }

        log.info("eval end run={} duration_ms={} report={}", runId, report.durationMs(), report.reportFile());
        return report;
    }

    private CaseResult evaluate(EvalCase c, EvalRun run,
                                String candidateProvider, String candidateModel,
                                String judgeProvider, String judgeModel) {
        Served served;
        try {
            served = CallContext.withSource(CallContext.SOURCE_EVAL,
                    () -> serve(c, run, candidateProvider, candidateModel));
        } catch (RuntimeException e) {
            return CaseResult.failed(c, "appel en echec : " + e.getMessage());
        }

        ChatService.ChatOutcome outcome = served.outcome();
        if (outcome.fallbackUsed() && !run.allowFallback()) {
            return CaseResult.excluded(c, outcome, "reponse servie par '" + outcome.provider()
                    + "' au lieu de '" + outcome.requestedProvider() + "' (fallback) : exclue de la mesure");
        }

        String answer = outcome.result().content();
        List<DimensionScore> scores = List.of(
                scoreCorrectness(c, answer, run, judgeProvider, judgeModel),
                served.schema() != null
                        ? scoreSchema(c, served.schema())
                        : scoreFormat(c, answer, run, judgeProvider, judgeModel),
                scoreHallucination(c, answer, run, judgeProvider, judgeModel));
        Boolean schemaValid = served.schema() == null ? null : served.schema().valid();
        return CaseResult.of(c, outcome, schemaValid, scores);
    }

    /** Un cas a schema passe par /chat/structured : c'est ce chemin-la qu'on veut mesurer. */
    private Served serve(EvalCase c, EvalRun run, String provider, String model) {
        // Meme forme qu'au chat : la consigne systeme d'abord, puis la question du cas.
        List<Message> messages = run.systemPrompt() == null || run.systemPrompt().isBlank()
                ? List.of(Message.user(c.prompt()))
                : List.of(Message.system(run.systemPrompt()), Message.user(c.prompt()));
        ChatOptions options = new ChatOptions(run.temperature(), null, run.maxTokens(), null);
        if (c.hasSchema()) {
            StructuredChatService.StructuredResult result =
                    structuredChatService.chat(provider, model, messages, c.schema(), options);
            return new Served(result.outcome(), result.validation());
        }
        return new Served(chatService.chat(provider, model, messages, options), null);
    }

    /**
     * FORMAT d'un cas a schema = verdict de SchemaValidator, rien d'autre. CORRECTNESS reste
     * note a part : une sortie conforme mais fausse doit sortir FORMAT PASS / CORRECTNESS FAIL.
     */
    private DimensionScore scoreSchema(EvalCase c, StructuredOutcome schema) {
        if (!c.scores(Dimension.FORMAT)) {
            return DimensionScore.skipped(Dimension.FORMAT, "non evalue pour ce cas");
        }
        return schema.valid()
                ? DimensionScore.pass(Dimension.FORMAT, "schema", "conforme au schema")
                : DimensionScore.fail(Dimension.FORMAT, "schema", String.join(" ; ", schema.violations()));
    }

    /** @param schema null pour un cas sans schema */
    private record Served(ChatService.ChatOutcome outcome, StructuredOutcome schema) {
    }

    private DimensionScore scoreCorrectness(EvalCase c, String answer, EvalRun run, String jp, String jm) {
        if (!c.scores(Dimension.CORRECTNESS)) {
            return DimensionScore.skipped(Dimension.CORRECTNESS, "non evalue pour ce cas");
        }
        Optional<DimensionScore> byRule = rules.correctness(c, answer);
        if (byRule.isPresent()) {
            return byRule.get();
        }
        if (!run.useJudge()) {
            return DimensionScore.skipped(Dimension.CORRECTNESS, "necessite le juge LLM (desactive)");
        }
        return judge.correctness(c, answer, jp, jm);
    }

    private DimensionScore scoreFormat(EvalCase c, String answer, EvalRun run, String jp, String jm) {
        if (!c.scores(Dimension.FORMAT)) {
            return DimensionScore.skipped(Dimension.FORMAT, "non evalue pour ce cas");
        }
        FormatOutcome outcome = rules.format(c, answer);
        if (!outcome.violations().isEmpty()) {
            return DimensionScore.fail(Dimension.FORMAT, "regle", String.join(" ; ", outcome.violations()));
        }
        if (outcome.requiredLanguage() != null) {
            if (!run.useJudge()) {
                return outcome.checks().isEmpty()
                        ? DimensionScore.skipped(Dimension.FORMAT, "langue : necessite le juge LLM (desactive)")
                        : DimensionScore.pass(Dimension.FORMAT, "regle",
                                String.join(" ; ", outcome.checks()) + " (langue non verifiee, juge desactive)");
            }
            DimensionScore language = judge.language(answer, outcome.requiredLanguage(), jp, jm);
            if (language.verdict() != Verdict.PASS) {
                return language;
            }
            List<String> checks = new ArrayList<>(outcome.checks());
            checks.add("langue " + outcome.requiredLanguage());
            return DimensionScore.pass(Dimension.FORMAT, "regle+" + language.method(), String.join(" ; ", checks));
        }
        if (outcome.checks().isEmpty()) {
            return DimensionScore.skipped(Dimension.FORMAT, "aucune regle de format applicable a ce cas");
        }
        return DimensionScore.pass(Dimension.FORMAT, "regle", String.join(" ; ", outcome.checks()));
    }

    private DimensionScore scoreHallucination(EvalCase c, String answer, EvalRun run, String jp, String jm) {
        if (!c.scores(Dimension.HALLUCINATION)) {
            return DimensionScore.skipped(Dimension.HALLUCINATION, "non evalue pour ce cas");
        }
        if (!run.useJudge()) {
            return DimensionScore.skipped(Dimension.HALLUCINATION, "necessite le juge LLM (desactive)");
        }
        return judge.hallucination(c, answer, jp, jm);
    }

    private List<String> warnings(EvalRun run, List<CaseResult> results,
                                  String candidateProvider, String candidateModel,
                                  String judgeProvider, String judgeModel) {
        List<String> warnings = new ArrayList<>();
        if (run.useJudge() && candidateProvider.equals(judgeProvider) && candidateModel.equals(judgeModel)) {
            warnings.add("Le juge est le meme modele que le candidat : biais d'auto-evaluation, "
                    + "les scores 'juge' sont probablement optimistes.");
        }
        long excluded = results.stream().filter(r -> r.error() != null && r.answer() != null).count();
        if (excluded > 0) {
            warnings.add(excluded + " cas servis par un fournisseur de repli : exclus de la mesure.");
        }
        long failed = results.stream().filter(r -> r.error() != null && r.answer() == null).count();
        if (failed > 0) {
            warnings.add(failed + " cas sans reponse (appel en echec).");
        }
        long truncated = results.stream().filter(r -> r.finishReason() == FinishReason.TRUNCATED).count();
        if (truncated > 0) {
            warnings.add(truncated + " reponses coupees par maxTokens : augmente maxTokens ou ces cas seront penalises a tort.");
        }
        long judgeErrors = results.stream()
                .flatMap(r -> r.scores().stream())
                .filter(s -> s.verdict() == Verdict.ERROR && s.method().startsWith("juge"))
                .count();
        if (judgeErrors > 0) {
            warnings.add(judgeErrors + " verdicts du juge illisibles : a relire a la main.");
        }
        return warnings;
    }

    /** Ce qui identifie une execution, fixe avant la boucle. */
    private record RunContext(String runId, Instant started, String candidate, String judge,
                              String datasetPath, String datasetHash, EvalReport.RunSettings settings) {
    }

    private EvalReport buildReport(RunContext context, List<CaseResult> results,
                                   List<String> warnings, String reportFile) {
        String currency = pricing.currency();

        Map<Dimension, EvalReport.DimensionStats> stats = new EnumMap<>(Dimension.class);
        for (Dimension d : Dimension.values()) {
            int passed = 0, failed = 0, skipped = 0, errors = 0;
            for (CaseResult r : results) {
                switch (r.verdict(d)) {
                    case PASS -> passed++;
                    case FAIL -> failed++;
                    case SKIPPED -> skipped++;
                    case ERROR -> errors++;
                }
            }
            Double rate = (passed + failed) == 0 ? null
                    : Math.round(1000.0 * passed / (passed + failed)) / 1000.0;
            stats.put(d, new EvalReport.DimensionStats(passed, failed, skipped, errors, rate));
        }

        Map<String, EvalReport.SchemaStats> schemaConformity = schemaConformity(results);

        List<Long> latencies = results.stream()
                .filter(r -> r.latencyMs() != null && r.error() == null)
                .map(CaseResult::latencyMs)
                .sorted()
                .toList();
        EvalReport.LatencyStats latency = latencies.isEmpty()
                ? new EvalReport.LatencyStats(null, null, null)
                : new EvalReport.LatencyStats(
                        Math.round(latencies.stream().mapToLong(Long::longValue).average().orElse(0)),
                        latencies.get(latencies.size() / 2),
                        latencies.get(latencies.size() - 1));

        int inputTokens = results.stream().filter(r -> r.inputTokens() != null).mapToInt(CaseResult::inputTokens).sum();
        int outputTokens = results.stream().filter(r -> r.outputTokens() != null).mapToInt(CaseResult::outputTokens).sum();
        List<Double> costs = results.stream().map(CaseResult::cost).filter(java.util.Objects::nonNull).toList();
        Double totalCost = costs.isEmpty() ? null : costs.stream().mapToDouble(Double::doubleValue).sum();
        int correct = stats.get(Dimension.CORRECTNESS).passed();
        // Tout ce qui a ete depense, divise par ce qui a ete reussi : un fournisseur bon
        // marche mais souvent faux coute plus cher par bonne reponse.
        Double costPerCorrect = (totalCost == null || correct == 0) ? null : totalCost / correct;

        return new EvalReport(context.runId(), context.started().toString(),
                Instant.now().toEpochMilli() - context.started().toEpochMilli(),
                context.candidate(), context.judge(), context.datasetPath(), context.datasetHash(),
                context.settings(), results.size(), stats, schemaConformity, latency, inputTokens,
                outputTokens, totalCost, costPerCorrect, currency, List.copyOf(warnings), results, reportFile);
    }

    private static Map<String, EvalReport.SchemaStats> schemaConformity(List<CaseResult> results) {
        Map<String, int[]> counts = new TreeMap<>();
        for (CaseResult r : results) {
            if (r.schemaValid() == null || r.error() != null) {
                continue;
            }
            int[] c = counts.computeIfAbsent(r.servedBy(), k -> new int[2]);
            c[0]++;
            if (r.schemaValid()) {
                c[1]++;
            }
        }
        Map<String, EvalReport.SchemaStats> stats = new TreeMap<>();
        counts.forEach((provider, c) -> stats.put(provider,
                new EvalReport.SchemaStats(c[0], c[1], Math.round(1000.0 * c[1] / c[0]) / 1000.0)));
        return stats;
    }

    private List<EvalCase> select(List<EvalCase> cases, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return cases;
        }
        List<EvalCase> selected = cases.stream().filter(c -> ids.contains(c.id())).toList();
        if (selected.size() != ids.size()) {
            List<String> known = cases.stream().map(EvalCase::id).toList();
            List<String> unknown = ids.stream().filter(id -> !known.contains(id)).toList();
            throw new EvalDatasetException("Ids inconnus dans le dataset : " + unknown);
        }
        return selected;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String sanitize(String text) {
        return text == null ? "default" : text.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}

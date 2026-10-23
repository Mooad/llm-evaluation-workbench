package com.aimastery.gateway.eval;

import com.aimastery.gateway.core.ChatService;
import com.aimastery.gateway.core.FinishReason;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Arrays;
import java.util.List;

/**
 * @param caseHash empreinte du cas evalue (CaseFingerprint) : null dans les rapports anterieurs
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaseResult(String id,
                         String caseHash,
                         String category,
                         String prompt,
                         String answer,
                         String servedBy,
                         boolean fallbackUsed,
                         String model,
                         Long latencyMs,
                         Integer inputTokens,
                         Integer outputTokens,
                         Double cost,
                         FinishReason finishReason,
                         Boolean schemaValid,
                         List<DimensionScore> scores,
                         String error) {

    /** @param schemaValid null quand le cas n'a pas de schema */
    static CaseResult of(EvalCase c, ChatService.ChatOutcome o, Boolean schemaValid, List<DimensionScore> scores) {
        return new CaseResult(c.id(), CaseFingerprint.of(c), c.category(), c.prompt(), o.result().content(),
                o.provider(), o.fallbackUsed(), o.result().model(), o.latencyMs(),
                o.result().usage().inputTokens(), o.result().usage().outputTokens(),
                o.cost().amount(), o.result().finishReason(), schemaValid, scores, null);
    }

    /** L'appel a echoue : aucune reponse a noter. */
    static CaseResult failed(EvalCase c, String error) {
        return new CaseResult(c.id(), CaseFingerprint.of(c), c.category(), c.prompt(), null, null, false, null,
                null, null, null, null, null, null, unscorable(c, error), error);
    }

    /** Une reponse existe, mais elle ne mesure pas le candidat (servie par un repli). */
    static CaseResult excluded(EvalCase c, ChatService.ChatOutcome o, String error) {
        return new CaseResult(c.id(), CaseFingerprint.of(c), c.category(), c.prompt(), o.result().content(),
                o.provider(), true, o.result().model(), o.latencyMs(),
                o.result().usage().inputTokens(), o.result().usage().outputTokens(),
                o.cost().amount(), o.result().finishReason(), null, unscorable(c, error), error);
    }

    private static List<DimensionScore> unscorable(EvalCase c, String reason) {
        return Arrays.stream(Dimension.values())
                .map(d -> c.scores(d)
                        ? DimensionScore.error(d, "-", reason)
                        : DimensionScore.skipped(d, "non evalue pour ce cas"))
                .toList();
    }

    public Verdict verdict(Dimension dimension) {
        return scores.stream()
                .filter(s -> s.dimension() == dimension)
                .map(DimensionScore::verdict)
                .findFirst()
                .orElse(Verdict.SKIPPED);
    }
}

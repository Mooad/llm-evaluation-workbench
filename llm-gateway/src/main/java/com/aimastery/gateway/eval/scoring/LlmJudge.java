package com.aimastery.gateway.eval.scoring;

import com.aimastery.gateway.core.ChatOptions;
import com.aimastery.gateway.core.ChatService;
import com.aimastery.gateway.core.Message;
import com.aimastery.gateway.eval.Dimension;
import com.aimastery.gateway.eval.DimensionScore;
import com.aimastery.gateway.eval.EvalCase;
import com.aimastery.gateway.observability.CallContext;
import com.aimastery.gateway.structured.JsonExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * LLM juge : pour ce qu'aucune regle ne sait trancher — le sens, la langue, l'invention.
 *
 * Il passe par le Gateway comme n'importe quel client (meme routage, memes logs).
 * Temperature 0 : un juge doit rendre le meme verdict a chaque passage.
 *
 * Limite a garder en tete : c'est un modele qui juge un modele. Il se trompe aussi,
 * et il est complaisant quand il se juge lui-meme. Toujours relire un echantillon.
 */
@Component
public class LlmJudge {

    private static final String SYSTEM =
            "You are a strict, impartial evaluator. You reply with a single JSON object and nothing else.";

    private static final String ANSWER_FORMAT =
            "Reply with ONLY this JSON object: {\"pass\": true or false, \"reason\": \"one short sentence\"}";

    /**
     * Raisonnement coupe : chez DeepSeek, il consomme le budget de tokens avant la
     * reponse (300 tokens de reflexion = verdict vide). Un verdict JSON court n'en a
     * pas besoin, et sans lui le juge reste rapide, bon marche et reproductible.
     */
    private static final ChatOptions JUDGE_OPTIONS = new ChatOptions(0.0, null, 300, false);

    private final ChatService chatService;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmJudge(ChatService chatService) {
        this.chatService = chatService;
    }

    public DimensionScore correctness(EvalCase c, String answer, String provider, String model) {
        String prompt = """
                QUESTION GIVEN TO THE ASSISTANT:
                %s

                REFERENCE:
                %s

                ASSISTANT ANSWER:
                %s

                Does the assistant answer satisfy the reference? Judge the meaning, not the exact wording.
                %s
                """.formatted(c.prompt(), reference(c), answer, ANSWER_FORMAT);
        return ask(Dimension.CORRECTNESS, prompt, provider, model);
    }

    public DimensionScore hallucination(EvalCase c, String answer, String provider, String model) {
        String prompt = """
                QUESTION / CONTEXT GIVEN TO THE ASSISTANT:
                %s

                REFERENCE:
                %s

                ASSISTANT ANSWER:
                %s

                A hallucination is any fact, value, name or claim in the answer that is supported
                neither by the question/context nor by the reference.
                Saying that the information is unavailable is NOT a hallucination.
                "pass": true means NO hallucination was found.
                %s
                """.formatted(c.prompt(), reference(c), answer, ANSWER_FORMAT);
        return ask(Dimension.HALLUCINATION, prompt, provider, model);
    }

    public DimensionScore language(String answer, String languageCode, String provider, String model) {
        String prompt = """
                TEXT:
                %s

                Is this text written in %s?
                Technical terms, acronyms, file formats, code and proper nouns kept in their
                original form (e.g. SRT, HTTP, Docker, JSON) do not count against it.
                %s
                """.formatted(answer, languageName(languageCode), ANSWER_FORMAT);
        return ask(Dimension.FORMAT, prompt, provider, model);
    }

    private DimensionScore ask(Dimension dimension, String prompt, String provider, String model) {
        ChatService.ChatOutcome outcome;
        try {
            outcome = CallContext.withSource(CallContext.SOURCE_EVAL_JUDGE, () -> chatService.chat(
                    provider, model,
                    List.of(Message.system(SYSTEM), Message.user(prompt)),
                    JUDGE_OPTIONS));
        } catch (RuntimeException e) {
            return DimensionScore.error(dimension, "juge", "appel du juge en echec : " + e.getMessage());
        }

        String method = "juge:" + outcome.provider() + "/" + outcome.result().model();
        String content = outcome.result().content();
        if (content == null || content.isBlank()) {
            // Sans ce detail, "pas de JSON : " ne dit pas si le juge a refuse, plante ou manque de budget.
            return DimensionScore.error(dimension, method, "reponse vide du juge (finish_reason="
                    + outcome.result().finishReason() + ", " + outcome.result().usage().outputTokens()
                    + " tokens de sortie)");
        }
        String json = JsonExtractor.extract(content);
        if (json == null) {
            return DimensionScore.error(dimension, method, "le juge n'a pas renvoye de JSON : " + abbreviate(content));
        }

        try {
            JsonNode verdict = mapper.readTree(json);
            JsonNode pass = verdict.get("pass");
            if (pass == null || !pass.isBoolean()) {
                return DimensionScore.error(dimension, method, "champ 'pass' absent ou non booleen : " + abbreviate(json));
            }
            String reason = verdict.path("reason").asText("");
            return pass.asBoolean()
                    ? DimensionScore.pass(dimension, method, reason)
                    : DimensionScore.fail(dimension, method, reason);
        } catch (Exception e) {
            return DimensionScore.error(dimension, method, "JSON du juge illisible : " + abbreviate(json));
        }
    }

    static String reference(EvalCase c) {
        List<String> parts = new ArrayList<>();
        if (c.expectedText() != null) {
            parts.add("Expected answer: " + c.expectedText());
        }
        if (c.hasExpectedObject()) {
            parts.add("Expected JSON: " + c.expectedAnswer());
        }
        if (c.expectedMeaning() != null) {
            parts.add("Expected meaning: " + c.expectedMeaning());
        }
        if (c.acceptedMeaning() != null) {
            parts.add("Acceptance criterion: " + c.acceptedMeaning());
        }
        if (c.expectedValues() != null && !c.expectedValues().isEmpty()) {
            parts.add("Example of a valid answer (other valid answers are acceptable): "
                    + String.join(", ", c.expectedValues()));
        }
        return parts.isEmpty() ? "(no reference provided)" : String.join("\n", parts);
    }

    private static String languageName(String code) {
        return switch (code == null ? "" : code.toLowerCase()) {
            case "fr" -> "French";
            case "en" -> "English";
            case "ar" -> "Arabic";
            case "es" -> "Spanish";
            default -> code;
        };
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 77) + "...";
    }
}

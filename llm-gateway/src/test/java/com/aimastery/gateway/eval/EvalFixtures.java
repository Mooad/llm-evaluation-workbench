package com.aimastery.gateway.eval;

import com.aimastery.gateway.config.GatewayProperties;
import com.aimastery.gateway.config.PricingProperties;
import com.aimastery.gateway.core.ChatCall;
import com.aimastery.gateway.core.ChatResult;
import com.aimastery.gateway.core.ChatService;
import com.aimastery.gateway.core.FallbackManager;
import com.aimastery.gateway.core.LlmProvider;
import com.aimastery.gateway.core.ProviderRegistry;
import com.aimastery.gateway.core.Usage;
import com.aimastery.gateway.eval.scoring.LlmJudge;
import com.aimastery.gateway.eval.scoring.RuleScorer;
import com.aimastery.gateway.observability.CallLogger;
import com.aimastery.gateway.structured.SchemaPromptBuilder;
import com.aimastery.gateway.structured.SchemaValidator;
import com.aimastery.gateway.structured.StructuredChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.DefaultResourceLoader;

/** Un EvalRunner complet sur de faux fournisseurs, sans modele ni reseau. */
public final class EvalFixtures {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalFixtures() {
    }

    public static EvalProperties properties(Path reportsDir, Path datasetsDir) {
        return new EvalProperties("classpath:evaluation/dataset.json", reportsDir.toString(), null, null,
                datasetsDir.toString(), 5.0, reportsDir.resolveSibling("baselines").toString());
    }

    public static EvalRunner runner(EvalProperties properties, LlmProvider... providers) {
        Map<String, GatewayProperties.ProviderConfig> configs = new LinkedHashMap<>();
        for (LlmProvider p : providers) {
            configs.put(p.name(), new GatewayProperties.ProviderConfig("http://localhost", p.defaultModel(), "", 5));
        }
        GatewayProperties gateway = new GatewayProperties(providers[0].name(), configs);
        ProviderRegistry registry = new ProviderRegistry(List.of(providers), gateway);
        ChatService chat = new ChatService(registry, new CallLogger(), new FallbackManager(registry, gateway));
        return new EvalRunner(chat,
                new StructuredChatService(chat, new SchemaPromptBuilder(), new SchemaValidator()),
                new EvalDatasetLoader(new DefaultResourceLoader(), MAPPER),
                new RuleScorer(), new LlmJudge(chat), properties, MAPPER, registry,
                new PricingProperties("USD", Map.of()));
    }

    /** Repond la meme chose a tout : suffisant pour produire un rapport. */
    public static LlmProvider constant(String name, String answer) {
        return new LlmProvider() {
            @Override public String name() { return name; }
            @Override public String defaultModel() { return name + "-model"; }
            @Override public ChatResult chat(ChatCall call) {
                return ChatResult.of(answer, name + "-model", new Usage(10, 5), "stop");
            }
        };
    }

    public static EvalRun rulesOnly(String provider, List<String> caseIds) {
        return new EvalRun(provider, null, null, caseIds, false, null, null, 0.0, 512, false);
    }
}

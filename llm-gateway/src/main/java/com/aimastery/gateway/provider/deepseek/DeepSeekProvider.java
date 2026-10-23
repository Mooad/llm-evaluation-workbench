package com.aimastery.gateway.provider.deepseek;

import com.aimastery.gateway.config.GatewayProperties;
import com.aimastery.gateway.config.RestClientConfig.RestClientFactory;
import com.aimastery.gateway.core.ChatOptions;
import com.aimastery.gateway.provider.openai.OpenAiCompatibleProvider;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * DeepSeek — distant, payant, format OpenAI. Sa seule particularite : le mode raisonnement
 * ("thinking"), dont les tokens sont pris sur max_tokens.
 */
@Component
public class DeepSeekProvider extends OpenAiCompatibleProvider {

    public static final String NAME = "deepseek";

    public DeepSeekProvider(GatewayProperties properties, RestClientFactory factory) {
        super(NAME, properties, factory);
    }

    @Override
    public boolean supportsReasoning() {
        return true;
    }

    /** Extension DeepSeek du format : {"thinking": {"type": "enabled" | "disabled"}}. */
    @Override
    protected void extend(Map<String, Object> body, ChatOptions options) {
        if (options.reasoning() != null) {
            body.put("thinking", Map.of("type", options.reasoning() ? "enabled" : "disabled"));
        }
    }
}

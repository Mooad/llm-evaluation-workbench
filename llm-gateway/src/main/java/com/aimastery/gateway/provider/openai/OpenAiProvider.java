package com.aimastery.gateway.provider.openai;

import com.aimastery.gateway.config.GatewayProperties;
import com.aimastery.gateway.config.RestClientConfig.RestClientFactory;
import org.springframework.stereotype.Component;

/**
 * OpenAI — distant, payant.
 *
 * Modele par defaut non raisonnant (gpt-4.1-mini) : les modeles gpt-5.x refusent
 * temperature 0, qu'utilisent l'evaluation et le juge, et comptent leur raisonnement
 * dans la limite de tokens. Choisir un gpt-5.x par requete reste possible.
 */
@Component
public class OpenAiProvider extends OpenAiCompatibleProvider {

    public static final String NAME = "openai";

    public OpenAiProvider(GatewayProperties properties, RestClientFactory factory) {
        super(NAME, properties, factory);
    }

    /** "max_tokens" est obsolete chez OpenAI et refuse par les modeles raisonnants. */
    @Override
    protected String maxTokensField() {
        return "max_completion_tokens";
    }
}

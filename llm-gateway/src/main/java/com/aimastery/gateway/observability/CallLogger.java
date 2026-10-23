package com.aimastery.gateway.observability;

import com.aimastery.gateway.core.CostEstimate;
import com.aimastery.gateway.core.FinishReason;
import com.aimastery.gateway.core.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Journalisation structuree de chaque appel.
 *
 * La latence SEULE n'est pas interpretable : "22 secondes" ne dit pas si le modele
 * est lent ou si l'entree etait enorme. On logge donc toujours latence ET tokens.
 */
@Component
public class CallLogger {

    private static final Logger log = LoggerFactory.getLogger("gateway.calls");

    public void success(String provider, String model, Usage usage, long latencyMs,
                        FinishReason finishReason, String providerFinishReason, CostEstimate cost) {
        // input_tokens n'est comparable qu'a lui-meme dans le temps : chaque fournisseur
        // compte avec son propre tokenizer, le meme texte ne donne pas le meme nombre.
        log.info("request_id={} source={} provider={} model={} status=ok input_tokens={} output_tokens={} "
                        + "latency_ms={} finish_reason={} provider_finish_reason={} cost={} currency={}",
                CallContext.requestId(), CallContext.source(),
                provider, model, nullSafe(usage.inputTokens()), nullSafe(usage.outputTokens()),
                latencyMs, finishReason, providerFinishReason == null ? "-" : providerFinishReason,
                cost != null && cost.isKnown() ? cost.amount() : "-",
                cost == null ? "-" : cost.currency());
    }

    public void failure(String provider, String model, long latencyMs, Exception error) {
        log.warn("request_id={} source={} provider={} model={} status=error latency_ms={} "
                        + "error_type={} error_message={}",
                CallContext.requestId(), CallContext.source(),
                provider, model, latencyMs, error.getClass().getSimpleName(), error.getMessage());
    }

    private Object nullSafe(Integer value) {
        return value == null ? "-" : value;
    }
}

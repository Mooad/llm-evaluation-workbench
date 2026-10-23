package com.aimastery.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aimastery.gateway.core.CostEstimate;
import com.aimastery.gateway.core.FinishReason;
import com.aimastery.gateway.core.Usage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class CallLoggerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger("gateway.calls");
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    @BeforeEach
    void capture() {
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void release() {
        logger.detachAppender(lines);
        MDC.clear();
    }

    @Test
    void chaque_ligne_porte_la_requete_et_la_source() {
        MDC.put(CallContext.REQUEST_ID, "req-1");

        CallContext.withSource(CallContext.SOURCE_EVAL_JUDGE, () -> {
            new CallLogger().success("ollama", "m", new Usage(3, 4), 10,
                    FinishReason.COMPLETE, "stop", CostEstimate.of(0, "USD", "local"));
            return null;
        });

        assertThat(lines.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .asString().startsWith("request_id=req-1 source=eval-judge provider=ollama ");
    }

    @Test
    void hors_requete_http_la_ligne_reste_parsable() {
        new CallLogger().failure("deepseek", "m", 5, new IllegalStateException("panne"));

        assertThat(lines.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .asString().startsWith("request_id=- source=api provider=deepseek ")
                .endsWith("error_message=panne");
    }
}

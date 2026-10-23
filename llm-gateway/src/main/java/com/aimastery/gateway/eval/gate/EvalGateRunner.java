package com.aimastery.gateway.eval.gate;

import com.aimastery.gateway.eval.regression.RegressionReport;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Point d'entree en ligne de commande du test de non-regression (scripts/eval-gate.sh).
 * Le code de sortie est le verdict : 0 = pas de regression, 1 = regression, 2 = pas de reference.
 */
@Component
@ConditionalOnProperty(name = "gateway.eval.gate.enabled", havingValue = "true")
public class EvalGateRunner implements ApplicationRunner {

    private final RegressionGate gate;
    private final GateProperties properties;
    private final ConfigurableApplicationContext context;

    public EvalGateRunner(RegressionGate gate, GateProperties properties, ConfigurableApplicationContext context) {
        this.gate = gate;
        this.properties = properties;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        GateResult result = gate.run(properties);
        print(result);
        int code = result.outcome().exitCode();
        System.exit(SpringApplication.exit(context, () -> code));
    }

    private static void print(GateResult result) {
        System.out.println();
        System.out.println("=== Test de non-regression : " + result.outcome() + " ===");
        System.out.println(result.message());
        RegressionReport r = result.regression();
        if (r != null) {
            r.changes().stream()
                    .filter(c -> c.kind() != RegressionReport.ChangeKind.IMPROVEMENT)
                    .forEach(c -> System.out.println("  en baisse : " + c.id() + " " + c.transitions().stream()
                            .map(t -> t.dimension() + " " + t.from() + "->" + t.to()).toList()));
            r.warnings().forEach(w -> System.out.println("  attention : " + w));
            System.out.println("Rapport de regression : " + r.file());
        }
        System.out.println("Rapport d'execution   : " + result.reportFile());
        System.out.println();
    }
}

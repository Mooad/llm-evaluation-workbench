package com.aimastery.gateway.eval.gate;

import com.aimastery.gateway.eval.regression.RegressionReport;

/**
 * @param regression null quand il n'y avait pas de reference a laquelle comparer
 */
public record GateResult(Outcome outcome, String message, RegressionReport regression, String reportFile) {

    public enum Outcome {
        /** Pas de regression : succes. */
        PASSED(0),
        /** Regression, ou plus rien de comparable : echec. */
        FAILED(1),
        /** Aucune reference : impossible de conclure. Relancer avec promote. */
        NO_BASELINE(2),
        /** Premiere execution enregistree comme reference. */
        BASELINE_CREATED(0);

        private final int exitCode;

        Outcome(int exitCode) {
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }
}

package com.aimastery.gateway.eval;

/** Execution annulee : aucun rapport n'est ecrit, un rapport partiel fausserait l'historique. */
public class EvalCancelledException extends RuntimeException {

    public EvalCancelledException(int done, int total) {
        super("Evaluation annulee apres " + done + "/" + total + " cas");
    }
}

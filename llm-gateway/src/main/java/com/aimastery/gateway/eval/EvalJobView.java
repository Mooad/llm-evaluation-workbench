package com.aimastery.gateway.eval;

/**
 * Etat d'une execution en arriere-plan.
 *
 * @param cancelling annulation demandee, effective a la fin du cas en cours
 * @param reportId   rapport ecrit, une fois l'execution terminee
 * @param provider   null = fournisseur par defaut du Gateway
 * @param judgeProvider null avec un juge actif = le candidat se juge lui-meme
 */
public record EvalJobView(String id,
                          Status status,
                          boolean cancelling,
                          String startedAt,
                          String finishedAt,
                          String datasetId,
                          String provider,
                          String model,
                          String judgeProvider,
                          String judgeModel,
                          int total,
                          int done,
                          String lastCase,
                          String reportId,
                          String error) {

    public enum Status { RUNNING, DONE, FAILED, CANCELLED }
}

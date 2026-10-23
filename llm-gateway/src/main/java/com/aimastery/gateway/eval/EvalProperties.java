package com.aimastery.gateway.eval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param datasetPath   dataset par defaut (classpath:... ou file:...)
 * @param reportsDir    ou ecrire les rapports JSON — un rapport par execution, pour comparer dans le temps
 * @param judgeProvider fournisseur du juge ; vide = celui du candidat (biais signale dans le rapport)
 * @param judgeModel    modele du juge ; vide = modele par defaut du fournisseur juge
 * @param datasetsDir   ou ranger les datasets importes depuis le portail
 * @param regressionThresholdPoints baisse de taux de reussite (en points) a partir de laquelle
 *                      une dimension est en regression : en dessous, c'est du bruit de mesure
 * @param baselinesDir  rapports de reference du test de non-regression, versionnes dans git
 */
@ConfigurationProperties(prefix = "gateway.eval")
public record EvalProperties(String datasetPath, String reportsDir, String judgeProvider, String judgeModel,
                             String datasetsDir, Double regressionThresholdPoints, String baselinesDir) {

    public EvalProperties {
        if (datasetsDir == null || datasetsDir.isBlank()) {
            datasetsDir = "datasets";
        }
        if (baselinesDir == null || baselinesDir.isBlank()) {
            baselinesDir = "eval-baselines";
        }
        if (regressionThresholdPoints == null) {
            regressionThresholdPoints = 5.0;
        }
        if (datasetPath == null || datasetPath.isBlank()) {
            datasetPath = "classpath:evaluation/dataset.json";
        }
        if (reportsDir == null || reportsDir.isBlank()) {
            reportsDir = "eval-reports";
        }
    }
}

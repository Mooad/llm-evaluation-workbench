package com.aimastery.gateway.eval;

import java.util.Map;

/**
 * @param hash  empreinte du dataset complet (CaseFingerprint) : deux rapports au meme hash
 *              ont evalue exactement les memes cas
 * @param error renseigne si le fichier est invalide : il reste liste pour qu'on le voie
 */
public record DatasetSummary(String id,
                             boolean builtIn,
                             int caseCount,
                             Map<String, Integer> categories,
                             int schemaCases,
                             String hash,
                             String error) {
}

package com.aimastery.gateway.eval.scoring;

import java.util.List;

/**
 * @param checks           regles de format appliquees a ce cas
 * @param violations       regles non respectees
 * @param requiredLanguage langue exigee : non verifiable par regle, a confier au juge
 */
public record FormatOutcome(List<String> checks, List<String> violations, String requiredLanguage) {
}

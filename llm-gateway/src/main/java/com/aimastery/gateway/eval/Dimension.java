package com.aimastery.gateway.eval;

/**
 * Les dimensions de notation. Notees SEPAREMENT : une reponse peut avoir un format
 * parfait et un contenu invente — une note unique masquerait le probleme.
 * La latence n'est pas un verdict : elle est mesuree sur chaque cas.
 */
public enum Dimension {
    CORRECTNESS,
    FORMAT,
    HALLUCINATION
}

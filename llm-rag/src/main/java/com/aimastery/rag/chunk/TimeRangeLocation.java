package com.aimastery.rag.chunk;

/**
 * Intervalle dans une source temporelle (video, audio), en millisecondes depuis le debut.
 * Prevu pour les transcriptions (Lab 2, Moviid) : c'est ce que la timeline affichera.
 */
public record TimeRangeLocation(long startMs, long endMs) implements SourceLocation {

    public static final String TYPE = "timeRange";

    public TimeRangeLocation {
        if (startMs < 0 || endMs < startMs) {
            throw new IllegalArgumentException("Intervalle invalide : " + startMs + " -> " + endMs);
        }
    }

    @Override
    public String label() {
        return format(startMs) + "-" + format(endMs);
    }

    private static String format(long ms) {
        long seconds = ms / 1000;
        return seconds >= 3600
                ? String.format("%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
                : String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}

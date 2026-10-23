package com.aimastery.rag.chunk;

/**
 * Plusieurs pages consecutives d'un fichier : un chunk qui suit une section du document au-dela
 * d'une page (strategie de decoupage par section).
 */
public record PageRangeLocation(int from, int to) implements SourceLocation {

    public static final String TYPE = "pageRange";

    public PageRangeLocation {
        if (from < 1 || to <= from) {
            throw new IllegalArgumentException("Plage de pages invalide : " + from + "-" + to
                    + " (une seule page = PageLocation)");
        }
    }

    public boolean contains(int page) {
        return page >= from && page <= to;
    }

    @Override
    public String label() {
        return "pages " + from + "-" + to;
    }
}

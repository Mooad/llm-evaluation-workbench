package com.aimastery.rag.chunk;

/** @param page numero de page du fichier (1 = premiere page), pas le numero imprime */
public record PageLocation(int page) implements SourceLocation {

    public static final String TYPE = "page";

    public PageLocation {
        if (page < 1) {
            throw new IllegalArgumentException("Une page commence a 1 : " + page);
        }
    }

    @Override
    public String label() {
        return "page " + page;
    }
}

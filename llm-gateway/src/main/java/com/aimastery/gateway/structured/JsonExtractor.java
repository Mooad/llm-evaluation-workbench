package com.aimastery.gateway.structured;

/**
 * Extrait le JSON d'une reponse de modele.
 *
 * Un LLM ne renvoie presque jamais du JSON nu : il l'entoure de texte, ou le met
 * dans un bloc de code. On ne peut donc pas passer la reponse brute a un parseur.
 */
public final class JsonExtractor {

    private JsonExtractor() {
    }

    /**
     * Renvoie le premier objet ou tableau JSON complet trouve, ou null.
     * On compte les accolades en ignorant celles qui sont dans une chaine.
     */
    public static String extract(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String text = stripCodeFence(raw.trim());

        int start = firstIndexOfAny(text, '{', '[');
        if (start < 0) {
            return null;
        }

        char open = text.charAt(start);
        char close = (open == '{') ? '}' : ']';

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }

            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String body = text.substring(firstNewline + 1);
        int closing = body.lastIndexOf("```");
        return (closing >= 0) ? body.substring(0, closing).trim() : body.trim();
    }

    private static int firstIndexOfAny(String text, char a, char b) {
        int ia = text.indexOf(a);
        int ib = text.indexOf(b);
        if (ia < 0) {
            return ib;
        }
        if (ib < 0) {
            return ia;
        }
        return Math.min(ia, ib);
    }
}

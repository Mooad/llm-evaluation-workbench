package com.aimastery.gateway.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Donne un identifiant a chaque requete HTTP et le renvoie dans X-Request-Id.
 * Tous les appels fournisseur faits pour cette requete (fallback, juge) le portent.
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /**
     * L'id fourni par l'appelant finit dans une ligne cle=valeur : un espace ou un saut
     * de ligne casserait le parsing, voire forgerait une fausse ligne de cout.
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        MDC.put(CallContext.REQUEST_ID, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CallContext.REQUEST_ID);
        }
    }

    private static String resolve(String supplied) {
        return supplied != null && SAFE_ID.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
    }
}

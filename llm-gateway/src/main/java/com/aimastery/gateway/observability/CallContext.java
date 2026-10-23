package com.aimastery.gateway.observability;

import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Contexte d'un appel, porte par le MDC du thread : a quelle requete HTTP il appartient,
 * et pour qui il est fait (trafic applicatif ou evaluation).
 *
 * Sans lui, les lignes de cout ne se regroupent pas : on ne sait ni relier les tentatives
 * d'un fallback, ni separer le cout d'une eval du trafic reel.
 */
public final class CallContext {

    public static final String REQUEST_ID = "request_id";
    public static final String SOURCE = "source";

    public static final String SOURCE_API = "api";
    public static final String SOURCE_EVAL = "eval";
    public static final String SOURCE_EVAL_JUDGE = "eval-judge";

    private CallContext() {
    }

    /** Execute l'action avec la source donnee, puis restaure la precedente. */
    public static <T> T withSource(String source, Supplier<T> action) {
        String previous = MDC.get(SOURCE);
        MDC.put(SOURCE, source);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                MDC.remove(SOURCE);
            } else {
                MDC.put(SOURCE, previous);
            }
        }
    }

    static String requestId() {
        String id = MDC.get(REQUEST_ID);
        return id == null ? "-" : id;
    }

    static String source() {
        String source = MDC.get(SOURCE);
        return source == null ? SOURCE_API : source;
    }
}

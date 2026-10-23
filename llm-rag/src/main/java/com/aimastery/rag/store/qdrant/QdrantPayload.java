package com.aimastery.rag.store.qdrant;

import com.aimastery.rag.chunk.Chunk;
import com.aimastery.rag.chunk.PageLocation;
import com.aimastery.rag.chunk.PageRangeLocation;
import com.aimastery.rag.chunk.SourceLocation;
import com.aimastery.rag.chunk.TimeRangeLocation;
import java.util.HashMap;
import java.util.Map;

/**
 * Chunk <-> payload Qdrant (metadonnees a plat).
 *
 * Les cles sont stockees telles quelles dans Qdrant : les renommer rend les points deja
 * indexes illisibles. D'ou leur regroupement ici, seul endroit qui connait ce format.
 */
final class QdrantPayload {

    static final String DOCUMENT_ID = "documentId";
    static final String CHUNK_INDEX = "chunkIndex";
    static final String FILE = "fichier";
    static final String SOURCE_TYPE = "sourceType";
    static final String LOCATION_TYPE = "locationType";
    static final String PAGE = "page";
    static final String START_MS = "startMs";
    static final String END_MS = "endMs";
    static final String SECTION = "section";
    static final String PAGE_TO = "pageTo";

    private QdrantPayload() {
    }

    static Map<String, Object> toPayload(Chunk chunk) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(DOCUMENT_ID, chunk.documentId());
        payload.put(CHUNK_INDEX, chunk.chunkIndex());
        payload.put(FILE, chunk.fileName());
        payload.put(SOURCE_TYPE, chunk.sourceType());
        if (chunk.section() != null) {
            payload.put(SECTION, chunk.section());
        }
        // Java 17 : pas de switch exhaustif sur l'interface scellee (Java 21). Le else qui leve
        // fait echouer le test de QdrantPayload des qu'un nouveau type n'est pas traite ici.
        SourceLocation location = chunk.location();
        if (location instanceof PageLocation page) {
            payload.put(LOCATION_TYPE, PageLocation.TYPE);
            payload.put(PAGE, page.page());
        } else if (location instanceof PageRangeLocation pages) {
            // "page" garde la premiere page : un filtre ou une lecture par page reste possible.
            payload.put(LOCATION_TYPE, PageRangeLocation.TYPE);
            payload.put(PAGE, pages.from());
            payload.put(PAGE_TO, pages.to());
        } else if (location instanceof TimeRangeLocation range) {
            payload.put(LOCATION_TYPE, TimeRangeLocation.TYPE);
            payload.put(START_MS, range.startMs());
            payload.put(END_MS, range.endMs());
        } else {
            throw new IllegalStateException("Localisation non geree par Qdrant : " + location);
        }
        return payload;
    }

    static Chunk toChunk(String id, String text, Map<String, Object> payload) {
        return new Chunk(
                id,
                (String) payload.get(DOCUMENT_ID),
                asLong(payload.get(CHUNK_INDEX)).intValue(),
                (String) payload.get(FILE),
                (String) payload.get(SOURCE_TYPE),
                text,
                toLocation(payload),
                (String) payload.get(SECTION));
    }

    /**
     * Les points ecrits avant l'introduction de locationType n'ont qu'une cle "page" :
     * on les lit comme des pages, plutot que d'imposer une reindexation.
     */
    static SourceLocation toLocation(Map<String, Object> payload) {
        Object type = payload.getOrDefault(LOCATION_TYPE, PageLocation.TYPE);
        if (TimeRangeLocation.TYPE.equals(type)) {
            return new TimeRangeLocation(asLong(payload.get(START_MS)), asLong(payload.get(END_MS)));
        }
        if (PageRangeLocation.TYPE.equals(type)) {
            return new PageRangeLocation(asLong(payload.get(PAGE)).intValue(), asLong(payload.get(PAGE_TO)).intValue());
        }
        if (PageLocation.TYPE.equals(type)) {
            return new PageLocation(asLong(payload.get(PAGE)).intValue());
        }
        throw new IllegalStateException("Type de localisation inconnu dans Qdrant : " + type);
    }

    // Qdrant stocke les entiers en 64 bits : ils reviennent en Long, quel que soit le type ecrit.
    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Valeur numerique attendue dans le payload Qdrant : " + value);
    }
}

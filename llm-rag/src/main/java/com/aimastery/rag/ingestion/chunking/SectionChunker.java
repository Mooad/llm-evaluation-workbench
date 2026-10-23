package com.aimastery.rag.ingestion.chunking;

import com.aimastery.rag.chunk.PageLocation;
import com.aimastery.rag.chunk.PageRangeLocation;
import com.aimastery.rag.chunk.SourceLocation;
import com.aimastery.rag.ingestion.source.ExtractedDocument;
import com.aimastery.rag.ingestion.source.Section;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoupage par structure : un chunk suit une section du document (sommaire PDF), quitte a
 * couvrir plusieurs pages, au lieu d'une fenetre de tokens qui coupe ou que tombe la limite.
 *
 * Hypothese testee : une procedure ou un tableau restent entiers, donc le modele les recoit
 * complets. Regles :
 * - les pages consecutives d'une meme section sont regroupees tant que le total tient dans
 *   maxTokens ; au-dela, un nouveau chunk commence, toujours en debut de page ;
 * - une page seule trop longue est redecoupee en fenetres de maxTokens, sans chevauchement ;
 * - une page sans section (document sans sommaire) reste seule : on ne devine pas la structure.
 */
public class SectionChunker implements Chunker {

    private final int maxTokens;
    private final TokenChunker oversized;
    private final Encoding encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    public SectionChunker(int maxTokens) {
        this.maxTokens = maxTokens;
        this.oversized = new TokenChunker(maxTokens, 0);
    }

    @Override
    public List<ChunkDraft> chunk(ExtractedDocument document) {
        List<ChunkDraft> chunks = new ArrayList<>();
        List<Section> group = new ArrayList<>();
        int groupTokens = 0;

        for (Section page : document.sections()) {
            int tokens = encoding.countTokens(page.text());
            boolean sameSection = !group.isEmpty() && page.title() != null
                    && page.title().equals(group.get(0).title());
            if (!group.isEmpty() && (!sameSection || groupTokens + tokens > maxTokens)) {
                flush(group, chunks);
                group.clear();
                groupTokens = 0;
            }
            if (tokens > maxTokens) {
                // Une page qui depasse a elle seule : redecoupee, chaque morceau garde sa page.
                chunks.addAll(oversized.chunk(new ExtractedDocument(List.of(page))));
                continue;
            }
            group.add(page);
            groupTokens += tokens;
        }
        if (!group.isEmpty()) {
            flush(group, chunks);
        }
        return chunks;
    }

    @Override
    public String description() {
        return "section(<=" + maxTokens + ")";
    }

    private static void flush(List<Section> group, List<ChunkDraft> chunks) {
        String text = String.join("\n\n", group.stream().map(Section::text).toList());
        chunks.add(new ChunkDraft(text, span(group), group.get(0).title()));
    }

    /** Les sections d'un PDF portent chacune une page : le chunk couvre de la premiere a la derniere. */
    private static SourceLocation span(List<Section> group) {
        SourceLocation first = group.get(0).location();
        SourceLocation last = group.get(group.size() - 1).location();
        if (group.size() > 1 && first instanceof PageLocation from && last instanceof PageLocation to) {
            return new PageRangeLocation(from.page(), to.page());
        }
        return first;
    }
}

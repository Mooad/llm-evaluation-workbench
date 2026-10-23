package com.aimastery.rag.ingestion.chunking;

import com.aimastery.rag.ingestion.source.ExtractedDocument;
import com.aimastery.rag.ingestion.source.Section;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

/**
 * Decoupage en fenetres de tokens, avec chevauchement, section par section.
 *
 * Le TokenTextSplitter de Spring AI decoupe proprement (il coupe de preference en fin de phrase)
 * mais ne sait pas faire de chevauchement. On decoupe donc en (taille - chevauchement) tokens,
 * puis on ajoute devant chaque chunk la fin du precedent : une phrase coupee a la frontiere
 * reste lisible en entier dans au moins un chunk, et le chunk tient dans la taille annoncee.
 *
 * Adapte aux sources ou chaque section est longue (pages). Le chevauchement s'arrete a la
 * section : un chunk ne cite qu'une localisation.
 */
public class TokenChunker implements Chunker {

    private final int chunkSize;
    private final int overlap;
    private final BodySplitter splitter;
    // Meme encodage que le TokenTextSplitter, sinon "overlap tokens" ne voudrait pas dire la meme chose.
    private final Encoding encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    public TokenChunker(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("La taille de chunk doit etre positive : " + chunkSize);
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "Le chevauchement doit etre entre 0 et la taille de chunk exclue : " + overlap + " / " + chunkSize);
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.splitter = new BodySplitter(chunkSize - overlap);
    }

    @Override
    public List<ChunkDraft> chunk(ExtractedDocument document) {
        List<ChunkDraft> chunks = new ArrayList<>();
        for (Section section : document.sections()) {
            String previousBody = null;
            for (String body : splitter.split(section.text())) {
                String text = previousBody == null || overlap == 0 ? body : tail(previousBody) + " " + body;
                chunks.add(new ChunkDraft(text, section.location(), section.title()));
                previousBody = body;
            }
        }
        return chunks;
    }

    @Override
    public String description() {
        return "token(" + chunkSize + "/" + overlap + ")";
    }

    /** Les derniers tokens du chunk precedent, recales sur un debut de mot. */
    private String tail(String previous) {
        IntArrayList tokens = encoding.encode(previous);
        if (tokens.size() <= overlap) {
            return previous;
        }
        IntArrayList last = new IntArrayList(overlap);
        for (int i = tokens.size() - overlap; i < tokens.size(); i++) {
            last.add(tokens.get(i));
        }
        String tail = encoding.decode(last);

        // Une coupure de token tombe souvent au milieu d'un mot, voire d'un caractere accentue :
        // un fragment comme "ession" n'aide pas la recherche, on repart du mot suivant.
        if (!tail.isEmpty() && !Character.isWhitespace(tail.charAt(0))) {
            int space = indexOfWhitespace(tail);
            if (space > 0) {
                tail = tail.substring(space);
            }
        }
        return tail.strip();
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Donne acces au decoupage texte -> textes, protege dans TokenTextSplitter. */
    private static final class BodySplitter extends TokenTextSplitter {

        // Hors taille, les valeurs par defaut de Spring AI : coupe en fin de phrase si elle tombe
        // apres 350 caracteres, ignore les morceaux de 5 caracteres ou moins, garde les sauts de ligne.
        BodySplitter(int chunkSize) {
            super(chunkSize, 350, 5, 10_000, true, List.of('.', '?', '!', '\n'));
        }

        List<String> split(String text) {
            return splitText(text);
        }
    }
}

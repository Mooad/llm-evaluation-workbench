package com.aimastery.rag.ingestion;

import com.aimastery.rag.chunk.Chunk;
import com.aimastery.rag.chunk.ChunkStore;
import com.aimastery.rag.ingestion.chunking.ChunkDraft;
import com.aimastery.rag.ingestion.source.ExtractedDocument;
import com.aimastery.rag.ingestion.source.SourceFile;
import com.aimastery.rag.ingestion.source.SourceFormat;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extract -> transform -> load, quel que soit le format.
 *
 * Le pipeline ne connait que des interfaces : le format fournit le reader et le chunker,
 * le ChunkStore cache la base vectorielle. Il ajoute ce qui est commun a tous les formats :
 * l'identite des chunks (document, index, identifiant stable) et la mesure.
 * Synchrone : l'asynchrone est l'affaire d'IngestionService.
 */
public class IngestionPipeline {

    private static final Logger LOG = LoggerFactory.getLogger("rag.ingestion");

    private final ChunkStore store;
    private final String embeddingModel;

    public IngestionPipeline(ChunkStore store, String embeddingModel) {
        this.store = store;
        this.embeddingModel = embeddingModel;
    }

    public IngestionResult ingest(String documentId, SourceFile file, SourceFormat format) {
        long start = System.nanoTime();

        ExtractedDocument extracted = format.reader().read(file);
        long read = System.nanoTime();

        List<Chunk> chunks = identify(documentId, file.fileName(), format.type(), format.chunker().chunk(extracted));
        store.replace(documentId, chunks);
        long end = System.nanoTime();

        // Une ligne cle=valeur par ingestion, comme gateway.calls : l'embedding domine, le
        // separer de la lecture dit ou optimiser ; le chunker et le modele disent comment
        // l'index a ete produit.
        LOG.info("documentId={} fichier={} format={} sections={} chunks={} chunker={} embeddingModel={} "
                        + "lectureMs={} embeddingEtEcritureMs={}",
                documentId, file.fileName(), format.type(), extracted.sections().size(), chunks.size(),
                format.chunker().description(), embeddingModel,
                (read - start) / 1_000_000, (end - read) / 1_000_000);

        return new IngestionResult(extracted.sections().size(), chunks.size());
    }

    /** L'index est continu sur tout le document : c'est lui qui rend l'identifiant stable. */
    private static List<Chunk> identify(String documentId, String fileName, String sourceType, List<ChunkDraft> drafts) {
        List<Chunk> chunks = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            ChunkDraft draft = drafts.get(index);
            chunks.add(new Chunk(Chunk.idFor(documentId, index), documentId, index, fileName, sourceType,
                    draft.text(), draft.location(), draft.section()));
        }
        return chunks;
    }
}

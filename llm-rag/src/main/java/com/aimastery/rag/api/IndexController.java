package com.aimastery.rag.api;

import com.aimastery.rag.document.DocumentRecord;
import com.aimastery.rag.document.DocumentRegistry;
import com.aimastery.rag.document.DocumentStatus;
import com.aimastery.rag.document.IndexState;
import com.aimastery.rag.index.SearchIndex;
import com.aimastery.rag.index.SearchIndexes;
import com.aimastery.rag.ingestion.IngestionService;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/indexes")
public class IndexController {

    private final SearchIndexes indexes;
    private final DocumentRegistry registry;
    private final IngestionService ingestion;

    public IndexController(SearchIndexes indexes, DocumentRegistry registry, IngestionService ingestion) {
        this.indexes = indexes;
        this.registry = registry;
        this.ingestion = ingestion;
    }

    /**
     * @param ready      documents prets dans cet index ; documents = tous les documents connus
     * @param processing en cours ; une reconstruction se suit en relisant cette liste
     */
    public record IndexView(String name, String label, String chunker, String collection, boolean isDefault,
                            int documents, int ready, int processing, int failed, int chunks) {
    }

    @GetMapping
    public List<IndexView> list() {
        List<DocumentRecord> documents = registry.all();
        return indexes.all().stream().map(index -> view(index, documents)).toList();
    }

    /** 202 : reindexation de tous les documents lancee, a partir des fichiers conserves. */
    @PostMapping("/{name}/rebuild")
    public ResponseEntity<IngestionService.RebuildResult> rebuild(@PathVariable String name) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ingestion.rebuild(name));
    }

    private IndexView view(SearchIndex index, List<DocumentRecord> documents) {
        List<IndexState> states = documents.stream().map(d -> d.index(index.name())).filter(Objects::nonNull).toList();
        return new IndexView(index.name(), index.label(), index.description(), index.collection(),
                index == indexes.defaultIndex(), documents.size(),
                count(states, DocumentStatus.READY), count(states, DocumentStatus.PROCESSING),
                count(states, DocumentStatus.FAILED),
                states.stream().map(IndexState::chunks).filter(Objects::nonNull).mapToInt(Integer::intValue).sum());
    }

    private static int count(List<IndexState> states, DocumentStatus status) {
        return (int) states.stream().filter(s -> s.status() == status).count();
    }
}

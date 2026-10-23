package com.aimastery.rag.api;

import com.aimastery.rag.api.dto.SubmitResponse;
import com.aimastery.rag.document.DocumentNotFoundException;
import com.aimastery.rag.document.DocumentRecord;
import com.aimastery.rag.document.DocumentRegistry;
import com.aimastery.rag.ingestion.IngestionService;
import com.aimastery.rag.ingestion.InvalidDocumentException;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final IngestionService ingestionService;
    private final DocumentRegistry registry;

    public DocumentController(IngestionService ingestionService, DocumentRegistry registry) {
        this.ingestionService = ingestionService;
        this.registry = registry;
    }

    /**
     * 202 : ingestion lancee, suivre GET /documents/{id}.
     * 200 : meme contenu deja indexe ou en cours, rien n'est relance.
     * collection : groupe du document, pour restreindre la recherche ; modifiable par un nouvel import.
     * force=true : reindexe un document deja pret (apres un changement de decoupage), meme documentId.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SubmitResponse> submit(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(defaultValue = "false") boolean force,
                                                 @RequestParam(required = false) String collection) throws IOException {
        if (file.isEmpty()) {
            throw new InvalidDocumentException("Fichier vide ou absent (champ multipart \"file\")");
        }
        String fileName = file.getOriginalFilename() == null ? "sans-nom" : file.getOriginalFilename();

        IngestionService.Submission submission = ingestionService.submit(fileName, file.getBytes(), force, collection);
        DocumentRecord document = submission.document();
        SubmitResponse body = new SubmitResponse(document.documentId(), document.status(), submission.alreadyKnown());

        return ResponseEntity.status(submission.alreadyKnown() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .location(URI.create("/documents/" + document.documentId()))
                .body(body);
    }

    @GetMapping
    public List<DocumentRecord> list() {
        return registry.all();
    }

    /** 204 : retire le document de tous les index, avec son fichier. 409 s'il est en cours d'indexation. */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable String documentId) {
        ingestionService.delete(documentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{documentId}")
    public DocumentRecord get(@PathVariable String documentId) {
        return registry.find(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
    }
}

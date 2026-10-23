package com.aimastery.rag.api;

import com.aimastery.rag.evaluation.EvalDataset;
import com.aimastery.rag.evaluation.ReportStore;
import com.aimastery.rag.evaluation.RetrievalEvaluator;
import com.aimastery.rag.evaluation.RetrievalReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/evaluations")
@Validated
public class EvaluationController {

    private final RetrievalEvaluator evaluator;
    private final ReportStore reports;

    public EvaluationController(RetrievalEvaluator evaluator, ReportStore reports) {
        this.evaluator = evaluator;
        this.reports = reports;
    }

    @GetMapping("/dataset")
    public EvalDataset dataset() {
        return evaluator.dataset();
    }

    /** Synchrone : une recherche par question, environ 1 s chacune avec bge-m3 en local. */
    @PostMapping("/retrieval")
    public RetrievalReport retrieval(@RequestParam(defaultValue = "5") @Min(1) @Max(20) int topK,
                                     @RequestParam(defaultValue = "ALL") @Pattern(regexp = "ALL|COLLECTION") String scope,
                                     @RequestParam(required = false) String index) {
        return evaluator.run(topK, scope, index);
    }

    @PostMapping("/reports")
    public ResponseEntity<JsonNode> save(@RequestBody ObjectNode report) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reports.save(report));
    }

    @GetMapping("/reports")
    public List<JsonNode> list() {
        return reports.list();
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<JsonNode> report(@PathVariable String id) {
        return reports.find(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}

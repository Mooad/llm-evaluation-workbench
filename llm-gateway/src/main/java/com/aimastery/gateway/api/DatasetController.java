package com.aimastery.gateway.api;

import com.aimastery.gateway.eval.DatasetCatalog;
import com.aimastery.gateway.eval.DatasetSummary;
import com.aimastery.gateway.eval.EvalCase;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Datasets d'evaluation : lister, consulter, importer. */
@RestController
@RequestMapping("/eval/datasets")
public class DatasetController {

    private final DatasetCatalog catalog;

    public DatasetController(DatasetCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<DatasetSummary> list() {
        return catalog.list();
    }

    @GetMapping("/{id}/cases")
    public List<EvalCase> cases(@PathVariable String id) {
        return catalog.cases(id);
    }

    /** Corps = le tableau de cas tel quel. Valide comme au chargement avant d'etre ecrit. */
    @PostMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DatasetSummary importDataset(@PathVariable String id, @RequestBody String json) {
        return catalog.save(id, json);
    }
}

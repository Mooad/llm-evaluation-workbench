package com.aimastery.gateway.api;

import com.aimastery.gateway.api.dto.EvalRunRequest;
import com.aimastery.gateway.eval.DatasetCatalog;
import com.aimastery.gateway.eval.EvalCase;
import com.aimastery.gateway.eval.EvalJobView;
import com.aimastery.gateway.eval.EvalJobs;
import com.aimastery.gateway.eval.EvalReport;
import com.aimastery.gateway.eval.EvalRunner;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Lancer une evaluation : en direct (/eval/run) ou en arriere-plan (/eval/runs). */
@RestController
@RequestMapping("/eval")
public class EvalController {

    private final EvalRunner runner;
    private final EvalJobs jobs;
    private final DatasetCatalog catalog;

    public EvalController(EvalRunner runner, EvalJobs jobs, DatasetCatalog catalog) {
        this.runner = runner;
        this.jobs = jobs;
        this.catalog = catalog;
    }

    /**
     * Synchrone : compter ~10-15 s par appel de modele local (candidat + juge).
     * Pour le CLI et Postman ; le portail passe par /eval/runs.
     */
    @PostMapping("/run")
    public EvalReport run(@RequestBody(required = false) EvalRunRequest request) {
        EvalRunRequest effective = (request == null) ? EvalRunRequest.defaults() : request;
        return runner.run(effective.toRun(datasetLocation(effective)));
    }

    /** Lance en arriere-plan et rend la main : suivre avec GET /eval/runs/{id}. */
    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EvalJobView start(@RequestBody(required = false) EvalRunRequest request) {
        EvalRunRequest effective = (request == null) ? EvalRunRequest.defaults() : request;
        String datasetId = effective.datasetId() == null ? DatasetCatalog.BUILT_IN : effective.datasetId();
        return jobs.start(effective.toRun(datasetLocation(effective)), datasetId);
    }

    @GetMapping("/runs")
    public List<EvalJobView> runs() {
        return jobs.list();
    }

    @GetMapping("/runs/{id}")
    public EvalJobView job(@PathVariable String id) {
        return jobs.get(id);
    }

    @PostMapping("/runs/{id}/cancel")
    public EvalJobView cancel(@PathVariable String id) {
        return jobs.cancel(id);
    }

    /** Verifie qu'un dataset se charge, sans appeler aucun modele. */
    @GetMapping("/dataset")
    public List<EvalCase> dataset(@RequestParam(required = false) String path) {
        return runner.dataset(path);
    }

    private String datasetLocation(EvalRunRequest request) {
        if (request.datasetId() != null && !request.datasetId().isBlank()) {
            return catalog.location(request.datasetId());
        }
        return request.datasetPath();
    }
}

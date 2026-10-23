package com.aimastery.gateway.api;

import com.aimastery.gateway.api.dto.RegressionRequest;
import com.aimastery.gateway.eval.EvalReport;
import com.aimastery.gateway.eval.ReportStore;
import com.aimastery.gateway.eval.ReportSummary;
import com.aimastery.gateway.eval.regression.RegressionReport;
import com.aimastery.gateway.eval.regression.RegressionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Rapports d'evaluation et rapports de regression. */
@RestController
@RequestMapping("/eval")
public class ReportController {

    private final ReportStore store;
    private final RegressionService regressions;

    public ReportController(ReportStore store, RegressionService regressions) {
        this.store = store;
        this.regressions = regressions;
    }

    @GetMapping("/reports")
    public List<ReportSummary> reports() {
        return store.list();
    }

    @GetMapping("/reports/{id}")
    public EvalReport report(@PathVariable String id) {
        return store.load(id);
    }

    @DeleteMapping("/reports/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        store.delete(id);
    }

    /** Compare deux rapports et ecrit le rapport de regression JSON (eval-reports/regressions/). */
    @PostMapping("/regressions")
    public RegressionReport compare(@Valid @RequestBody RegressionRequest request) {
        return regressions.compare(request.baseline(), request.candidate(), request.thresholdPoints());
    }
}

package com.aimastery.gateway.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Evaluations lancees en arriere-plan : une execution dure plusieurs minutes avec un modele
 * local, le portail suit sa progression au lieu d'attendre une requete bloquee.
 *
 * Seul l'etat des executions recentes est garde en memoire ; le resultat, lui, est le
 * rapport ecrit sur disque. Un redemarrage perd la progression, jamais un rapport.
 */
@Component
public class EvalJobs {

    private static final Logger log = LoggerFactory.getLogger(EvalJobs.class);
    private static final int KEPT = 20;

    private final EvalRunner runner;
    private final TaskExecutor executor;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private Job current;

    public EvalJobs(EvalRunner runner, TaskExecutor executor) {
        this.runner = runner;
        this.executor = executor;
    }

    public synchronized EvalJobView start(EvalRun run, String datasetId) {
        if (current != null && current.status == EvalJobView.Status.RUNNING) {
            throw new EvalAlreadyRunningException(current.id);
        }
        int total = runner.preflight(run);
        Job job = new Job(UUID.randomUUID().toString().substring(0, 8), datasetId, run, total);
        remember(job);
        current = job;

        Map<String, String> context = MDC.getCopyOfContextMap();
        executor.execute(() -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                EvalReport report = runner.run(run, job);
                job.finish(EvalJobView.Status.DONE, ReportStore.idOf(report.reportFile()),
                        report.reportFile() == null ? "rapport non ecrit sur disque" : null);
            } catch (EvalCancelledException e) {
                job.finish(EvalJobView.Status.CANCELLED, null, e.getMessage());
            } catch (RuntimeException e) {
                log.error("evaluation {} en echec", job.id, e);
                job.finish(EvalJobView.Status.FAILED, null, e.getMessage());
            } finally {
                MDC.clear();
            }
        });
        return job.view();
    }

    public synchronized List<EvalJobView> list() {
        List<EvalJobView> views = new ArrayList<>();
        jobs.values().forEach(job -> views.add(0, job.view()));
        return views;
    }

    public synchronized EvalJobView get(String id) {
        return find(id).view();
    }

    /** Pris en compte entre deux cas : l'appel de modele en cours va jusqu'au bout. */
    public synchronized EvalJobView cancel(String id) {
        Job job = find(id);
        job.cancelRequested = true;
        return job.view();
    }

    private Job find(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new EvalJobNotFoundException(id);
        }
        return job;
    }

    private void remember(Job job) {
        jobs.put(job.id, job);
        while (jobs.size() > KEPT) {
            jobs.remove(jobs.keySet().iterator().next());
        }
    }

    /** Etat mutable d'une execution ; ecrit par le thread d'evaluation, lu par les requetes. */
    private static final class Job implements EvalProgress {
        private final String id;
        private final String datasetId;
        private final EvalRun run;
        private final Instant startedAt = Instant.now();
        private volatile int total;
        private volatile int done;
        private volatile String lastCase;
        private volatile boolean cancelRequested;
        private volatile EvalJobView.Status status = EvalJobView.Status.RUNNING;
        private volatile Instant finishedAt;
        private volatile String reportId;
        private volatile String error;

        Job(String id, String datasetId, EvalRun run, int total) {
            this.id = id;
            this.datasetId = datasetId;
            this.run = run;
            this.total = total;
        }

        @Override
        public void started(int totalCases) {
            this.total = totalCases;
        }

        @Override
        public void caseDone(String caseId, int doneCases) {
            this.lastCase = caseId;
            this.done = doneCases;
        }

        @Override
        public boolean cancelled() {
            return cancelRequested;
        }

        void finish(EvalJobView.Status status, String reportId, String error) {
            this.reportId = reportId;
            this.error = error;
            this.finishedAt = Instant.now();
            this.status = status;
        }

        EvalJobView view() {
            return new EvalJobView(id, status, cancelRequested && status == EvalJobView.Status.RUNNING,
                    startedAt.toString(), finishedAt == null ? null : finishedAt.toString(),
                    datasetId, run.provider(), run.model(), run.useJudge() ? run.judgeProvider() : null,
                    run.useJudge() ? run.judgeModel() : null, total, done, lastCase, reportId, error);
        }
    }
}

package com.aimastery.gateway.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalJobsTest {

    @TempDir
    Path dir;

    private EvalRunner runner() {
        return EvalFixtures.runner(EvalFixtures.properties(dir.resolve("reports"), dir.resolve("datasets")),
                EvalFixtures.constant("local", "Rabat"));
    }

    @Test
    void une_execution_terminee_pointe_vers_son_rapport() {
        EvalJobs jobs = new EvalJobs(runner(), Runnable::run);

        EvalJobView started = jobs.start(EvalFixtures.rulesOnly("local", List.of("fact_001", "math_001")), "golden");
        EvalJobView done = jobs.get(started.id());

        assertThat(done.status()).isEqualTo(EvalJobView.Status.DONE);
        assertThat(done.done()).isEqualTo(2);
        assertThat(done.reportId()).startsWith("eval-");
    }

    @Test
    void refuse_une_deuxieme_execution_pendant_la_premiere() {
        List<Runnable> pending = new ArrayList<>();
        EvalJobs jobs = new EvalJobs(runner(), pending::add);
        jobs.start(EvalFixtures.rulesOnly("local", List.of("fact_001")), "golden");

        assertThatThrownBy(() -> jobs.start(EvalFixtures.rulesOnly("local", List.of("fact_001")), "golden"))
                .isInstanceOf(EvalAlreadyRunningException.class);
    }

    @Test
    void une_execution_annulee_n_ecrit_aucun_rapport() {
        List<Runnable> pending = new ArrayList<>();
        EvalJobs jobs = new EvalJobs(runner(), pending::add);
        EvalJobView started = jobs.start(EvalFixtures.rulesOnly("local", null), "golden");

        assertThat(jobs.cancel(started.id()).cancelling()).isTrue();
        pending.forEach(Runnable::run);

        EvalJobView cancelled = jobs.get(started.id());
        assertThat(cancelled.status()).isEqualTo(EvalJobView.Status.CANCELLED);
        assertThat(cancelled.reportId()).isNull();
        assertThat(dir.resolve("reports")).doesNotExist();
    }

    @Test
    void un_id_de_cas_inconnu_est_refuse_avant_le_lancement() {
        EvalJobs jobs = new EvalJobs(runner(), Runnable::run);

        assertThatThrownBy(() -> jobs.start(EvalFixtures.rulesOnly("local", List.of("inexistant")), "golden"))
                .isInstanceOf(EvalDatasetException.class);
        assertThat(jobs.list()).isEmpty();
    }
}

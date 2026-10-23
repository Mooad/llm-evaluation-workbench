import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { map } from 'rxjs';
import { EvalApi } from '../core/eval-api';
import { ReportSummary } from '../core/eval.models';
import { RegressionView } from './regression-view';
import { ReportDetail } from './report-detail';
import { ReportHistory } from './report-history';
import { RunLauncher } from './run-launcher';

/**
 * Evaluations : lancer, lire un rapport, mesurer la regression entre deux executions.
 *
 * Ce qui est affiche vit dans l'URL (?report=, ?base=&cand=) : un rechargement ou un lien
 * partage rouvre exactement la meme vue.
 */
@Component({
  selector: 'app-evaluations',
  imports: [RunLauncher, ReportHistory, ReportDetail, RegressionView],
  templateUrl: './evaluations.html',
  styleUrl: './evaluations.scss',
})
export class Evaluations {
  private readonly api = inject(EvalApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  private readonly params = toSignal(this.route.queryParamMap.pipe(map((p) => ({
    report: p.get('report'),
    base: p.get('base'),
    cand: p.get('cand'),
  }))), { initialValue: { report: null, base: null, cand: null } });

  protected readonly reportId = computed(() => this.params().report);
  protected readonly baseId = computed(() => this.params().base);
  protected readonly candId = computed(() => this.params().cand);

  protected readonly reports = signal<ReportSummary[]>([]);
  protected readonly loadError = signal<string | null>(null);

  /** Execution precedente du meme candidat sur le meme dataset : la reference naturelle. */
  protected readonly previousOfOpen = computed(() => {
    const id = this.reportId();
    return id ? (this.previousOf(id)?.id ?? null) : null;
  });

  constructor() {
    this.refresh();
  }

  protected open(id: string): void {
    this.navigate({ report: id });
  }

  /** La plus ancienne des deux devient la reference : on mesure ce qui a change depuis. */
  protected compare([a, b]: [string, string]): void {
    const started = (id: string) => this.reports().find((r) => r.id === id)?.startedAt ?? '';
    const [base, cand] = started(a) <= started(b) ? [a, b] : [b, a];
    this.navigate({ base, cand });
  }

  protected swap(): void {
    this.navigate({ base: this.candId(), cand: this.baseId() });
  }

  protected close(): void {
    this.navigate({});
  }

  /** Fin d'execution : regression contre la precedente si elle existe, sinon le rapport. */
  protected onRunFinished(reportId: string): void {
    this.refresh(() => {
      const previous = this.previousOf(reportId);
      this.navigate(previous ? { base: previous.id, cand: reportId } : { report: reportId });
    });
  }

  protected onDeleted(): void {
    this.navigate({});
    this.refresh();
  }

  /**
   * Reference naturelle d'un rapport : l'execution precedente du meme candidat sur le meme
   * dataset, de preference avec le meme juge — sinon l'ecart melangerait candidat et juge.
   */
  private previousOf(id: string): ReportSummary | undefined {
    const current = this.reports().find((r) => r.id === id);
    if (!current) {
      return undefined;
    }
    const earlier = this.reports().filter(
      (r) =>
        r.id !== id &&
        r.datasetId === current.datasetId &&
        r.candidate === current.candidate &&
        r.startedAt < current.startedAt,
    );
    return earlier.find((r) => r.judge === current.judge) ?? earlier[0];
  }

  private refresh(then?: () => void): void {
    this.api.reports().subscribe({
      next: (reports) => {
        this.reports.set(reports);
        this.loadError.set(null);
        then?.();
      },
      error: () => this.loadError.set('Rapports indisponibles : le Gateway répond-il ?'),
    });
  }

  private navigate(params: { report?: string | null; base?: string | null; cand?: string | null }): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { report: params.report ?? null, base: params.base ?? null, cand: params.cand ?? null },
    });
  }
}

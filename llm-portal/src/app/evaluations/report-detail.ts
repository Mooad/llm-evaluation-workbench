import { Component, computed, inject, input, output, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';
import { EvalApi } from '../core/eval-api';
import { CaseResult, DIMENSIONS, Dimension, EvalReport, Verdict } from '../core/eval.models';
import { downloadJson, formatCost, formatCount, formatDateTime, formatMs, formatRate } from '../core/format';

type Filter = 'all' | 'fail' | 'error' | 'schema';

const FILTERS: { key: Filter; label: string }[] = [
  { key: 'all', label: 'Tous' },
  { key: 'fail', label: 'Échecs' },
  { key: 'error', label: 'Erreurs' },
  { key: 'schema', label: 'Avec schéma' },
];

type State = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; report: EvalReport };

/** Un rapport : ses indicateurs, puis chaque cas avec ses verdicts et leurs raisons. */
@Component({
  selector: 'app-report-detail',
  templateUrl: './report-detail.html',
  styleUrl: './report-detail.scss',
})
export class ReportDetail {
  private readonly api = inject(EvalApi);

  readonly reportId = input.required<string>();
  /** Execution precedente du meme candidat sur le meme dataset, si elle existe. */
  readonly previousId = input<string | null>(null);

  readonly compareWith = output<[string, string]>();
  readonly deleted = output<string>();
  readonly closed = output<void>();

  protected readonly dimensions = DIMENSIONS;
  protected readonly filters = FILTERS;
  protected readonly formatRate = formatRate;
  protected readonly formatCost = formatCost;
  protected readonly formatCount = formatCount;
  protected readonly formatMs = formatMs;
  protected readonly formatDateTime = formatDateTime;

  protected readonly state = toSignal(
    toObservable(this.reportId).pipe(
      switchMap((id) =>
        this.api.report(id).pipe(
          map((report): State => ({ status: 'ready', report })),
          catchError((e) => of<State>({ status: 'error', message: e.error?.message ?? 'Rapport illisible' })),
          startWith<State>({ status: 'loading' }),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } as State },
  );

  protected readonly report = computed(() => {
    const s = this.state();
    return s.status === 'ready' ? s.report : null;
  });

  protected readonly filter = signal<Filter>('all');
  protected readonly expanded = signal<string | null>(null);
  protected readonly confirmingDelete = signal(false);

  protected readonly counts = computed((): Record<Filter, number> => {
    const cases = this.report()?.cases ?? [];
    return {
      all: cases.length,
      fail: cases.filter((c) => this.hasVerdict(c, 'FAIL')).length,
      error: cases.filter((c) => c.error !== null || this.hasVerdict(c, 'ERROR')).length,
      schema: cases.filter((c) => c.schemaValid !== null).length,
    };
  });

  protected readonly visibleCases = computed(() => {
    const cases = this.report()?.cases ?? [];
    switch (this.filter()) {
      case 'fail':
        return cases.filter((c) => this.hasVerdict(c, 'FAIL'));
      case 'error':
        return cases.filter((c) => c.error !== null || this.hasVerdict(c, 'ERROR'));
      case 'schema':
        return cases.filter((c) => c.schemaValid !== null);
      default:
        return cases;
    }
  });

  protected readonly schemaRate = computed(() => {
    const stats = Object.values(this.report()?.schemaConformity ?? {});
    const cases = stats.reduce((sum, s) => sum + s.cases, 0);
    return cases === 0 ? null : stats.reduce((sum, s) => sum + s.conform, 0) / cases;
  });

  protected verdict(c: CaseResult, d: Dimension): Verdict {
    return c.scores.find((s) => s.dimension === d)?.verdict ?? 'SKIPPED';
  }

  protected toggle(id: string): void {
    this.expanded.update((current) => (current === id ? null : id));
  }

  protected download(report: EvalReport): void {
    downloadJson(report, `${this.reportId()}.json`);
  }

  protected remove(): void {
    if (!this.confirmingDelete()) {
      this.confirmingDelete.set(true);
      return;
    }
    this.api.deleteReport(this.reportId()).subscribe(() => this.deleted.emit(this.reportId()));
  }

  private hasVerdict(c: CaseResult, verdict: Verdict): boolean {
    return c.scores.some((s) => s.verdict === verdict);
  }
}

import { Component, computed, inject, input, output, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, combineLatest, debounceTime, map, of, startWith, switchMap } from 'rxjs';
import { EvalApi } from '../core/eval-api';
import { DIMENSIONS, RegressionReport, RegressionStatus } from '../core/eval.models';
import {
  downloadJson,
  formatCost,
  formatDateTime,
  formatMs,
  formatPercentDelta,
  formatPoints,
  formatRate,
} from '../core/format';

type State =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; regression: RegressionReport };

const STATUS_LABELS: Record<RegressionStatus, string> = {
  REGRESSION: 'Régression',
  IMPROVEMENT: 'Amélioration',
  STABLE: 'Stable',
  NOT_COMPARABLE: 'Non comparable',
};

/**
 * Rapport de regression entre deux executions. Le calcul est fait par le Gateway, qui ecrit
 * aussi le JSON : l'ecran ne fait que le rendre lisible.
 */
@Component({
  selector: 'app-regression-view',
  templateUrl: './regression-view.html',
  styleUrl: './regression-view.scss',
})
export class RegressionView {
  private readonly api = inject(EvalApi);

  readonly baselineId = input.required<string>();
  readonly candidateId = input.required<string>();

  readonly swap = output<void>();
  readonly openReport = output<string>();
  readonly closed = output<void>();

  protected readonly dimensions = DIMENSIONS;
  protected readonly labels = STATUS_LABELS;
  protected readonly formatRate = formatRate;
  protected readonly formatPoints = formatPoints;
  protected readonly formatPercentDelta = formatPercentDelta;
  protected readonly formatCost = formatCost;
  protected readonly formatMs = formatMs;
  protected readonly formatDateTime = formatDateTime;

  /** null = seuil de configuration du Gateway. */
  protected readonly threshold = signal<number | null>(null);

  protected readonly state = toSignal(
    combineLatest([
      toObservable(this.baselineId),
      toObservable(this.candidateId),
      toObservable(this.threshold).pipe(debounceTime(300)),
    ]).pipe(
      switchMap(([baseline, candidate, threshold]) =>
        this.api.compare(baseline, candidate, threshold ?? undefined).pipe(
          map((regression): State => ({ status: 'ready', regression })),
          catchError((e: HttpErrorResponse) =>
            of<State>({ status: 'error', message: e.error?.message ?? 'Comparaison impossible' }),
          ),
          startWith<State>({ status: 'loading' }),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } as State },
  );

  protected readonly regression = computed(() => {
    const s = this.state();
    return s.status === 'ready' ? s.regression : null;
  });

  protected readonly regressions = computed(() => this.regression()?.changes.filter((c) => c.kind !== 'IMPROVEMENT') ?? []);
  protected readonly improvements = computed(() => this.regression()?.changes.filter((c) => c.kind === 'IMPROVEMENT') ?? []);

  /**
   * Phrase principale : en regression, la dimension qui baisse le plus ; sinon, celle qui
   * bouge le plus. Toujours chiffree et rapportee au nombre de cas compares.
   */
  protected readonly headline = computed(() => {
    const r = this.regression();
    if (!r) {
      return '';
    }
    if (r.status === 'NOT_COMPARABLE') {
      return 'Aucun cas commun noté des deux côtés : rien à comparer.';
    }
    const moved = DIMENSIONS.map((d) => ({ d, delta: r.dimensions[d] })).filter((x) => x.delta.deltaPoints !== null);
    const worst =
      r.status === 'REGRESSION'
        ? moved.sort((a, b) => (a.delta.deltaPoints ?? 0) - (b.delta.deltaPoints ?? 0))[0]
        : moved.sort((a, b) => Math.abs(b.delta.deltaPoints ?? 0) - Math.abs(a.delta.deltaPoints ?? 0))[0];
    const regressed = this.regressions().length;
    const improved = this.improvements().length;
    const lead = worst
      ? `${worst.d} ${formatRate(worst.delta.baselineRate)} → ${formatRate(worst.delta.candidateRate)} (${formatPoints(worst.delta.deltaPoints)})`
      : '';
    return `${lead} sur ${r.coverage.common} cas communs · ${regressed} cas en baisse, ${improved} en hausse.`;
  });

  protected onThreshold(value: string): void {
    const parsed = Number(value.replace(',', '.'));
    this.threshold.set(value.trim() === '' || !Number.isFinite(parsed) ? null : Math.max(0, parsed));
  }

  protected download(r: RegressionReport): void {
    downloadJson(r, `regression-${r.baseline.runId}-vs-${r.candidate.runId}.json`);
  }

  /** Plus bas = mieux pour un cout ou une latence : la couleur suit le sens du progres. */
  protected tone(before: number | null | undefined, after: number | null | undefined): string {
    if (before === null || before === undefined || after === null || after === undefined || before === after) {
      return '';
    }
    return after < before ? 'up' : 'down';
  }
}

import { Component, computed, input, output, signal } from '@angular/core';
import { DIMENSIONS, Dimension, ReportSummary } from '../core/eval.models';
import { formatCost, formatDateTime, formatMs, formatRate } from '../core/format';

/** Historique des executions : ouvrir un rapport, en choisir deux a comparer. */
@Component({
  selector: 'app-report-history',
  templateUrl: './report-history.html',
  styleUrl: './report-history.scss',
})
export class ReportHistory {
  readonly reports = input.required<ReportSummary[]>();
  readonly active = input<string | null>(null);

  readonly open = output<string>();
  /** Deux ids, dans l'ordre de selection : la page en deduit reference et candidat. */
  readonly compare = output<[string, string]>();

  protected readonly dimensions = DIMENSIONS;
  protected readonly formatRate = formatRate;
  protected readonly formatCost = formatCost;
  protected readonly formatMs = formatMs;
  protected readonly formatDateTime = formatDateTime;

  protected readonly datasetFilter = signal('');
  protected readonly selected = signal<string[]>([]);

  protected readonly datasetIds = computed(() => [...new Set(this.reports().map((r) => r.datasetId))].sort());
  protected readonly visible = computed(() =>
    this.datasetFilter() ? this.reports().filter((r) => r.datasetId === this.datasetFilter()) : this.reports(),
  );

  protected rate(report: ReportSummary, dimension: Dimension): number | null {
    return report.dimensions?.[dimension]?.passRate ?? null;
  }

  protected toggle(id: string): void {
    this.selected.update((ids) =>
      // Au-dela de deux, la plus ancienne selection cede la place.
      ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id].slice(-2),
    );
  }

  protected compareSelected(): void {
    const [a, b] = this.selected();
    if (a && b) {
      this.compare.emit([a, b]);
    }
  }
}

import { HttpErrorResponse } from '@angular/common/http';
import { KeyValuePipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, interval, switchMap, takeWhile } from 'rxjs';
import { EvalApi } from '../core/eval-api';
import { DatasetSummary, EvalCase, JobView, RunRequest } from '../core/eval.models';
import { GatewayConfig } from '../core/gateway-config';
import { formatMs } from '../core/format';

const POLL_MS = 1000;

/** Choisir un dataset (ou en importer un), regler l'execution, la lancer et suivre sa progression. */
@Component({
  selector: 'app-run-launcher',
  imports: [KeyValuePipe],
  templateUrl: './run-launcher.html',
  styleUrl: './run-launcher.scss',
})
export class RunLauncher {
  private readonly api = inject(EvalApi);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly config = inject(GatewayConfig);
  protected readonly formatMs = formatMs;

  /** Emis avec l'id du rapport ecrit, a la fin d'une execution reussie. */
  readonly finished = output<string>();

  protected readonly datasets = signal<DatasetSummary[]>([]);
  protected readonly datasetId = signal('golden');
  protected readonly dataset = computed(() => this.datasets().find((d) => d.id === this.datasetId()));
  protected readonly cases = signal<EvalCase[]>([]);
  protected readonly showCases = signal(false);
  protected readonly category = signal('');

  protected readonly scope = computed(() =>
    this.category() ? this.cases().filter((c) => (c.category ?? '-') === this.category()) : this.cases(),
  );

  protected readonly provider = signal('');
  protected readonly model = signal('');
  protected readonly useJudge = signal(true);
  protected readonly judgeProvider = signal('');
  protected readonly judgeModel = signal('');
  protected readonly temperature = signal('0');
  protected readonly maxTokens = signal('512');
  protected readonly allowFallback = signal(false);
  protected readonly systemPrompt = signal('');

  /** Le juge note le candidat avec le meme modele : biais d'auto-evaluation, a eviter. */
  protected readonly selfJudged = computed(
    () =>
      this.useJudge() &&
      (this.judgeProvider() || this.provider()) === this.provider() &&
      (this.judgeModel() || '') === (this.model() || ''),
  );

  protected readonly importJson = signal<string | null>(null);
  protected readonly importName = signal('');
  protected readonly importError = signal<string | null>(null);

  protected readonly job = signal<JobView | null>(null);
  protected readonly startError = signal<string | null>(null);
  protected readonly running = computed(() => this.job()?.status === 'RUNNING');
  protected readonly now = signal(Date.now());
  protected readonly elapsedMs = computed(() => {
    const job = this.job();
    if (!job) {
      return null;
    }
    const end = job.finishedAt ? Date.parse(job.finishedAt) : this.now();
    return end - Date.parse(job.startedAt);
  });
  protected readonly percent = computed(() => {
    const job = this.job();
    return job && job.total > 0 ? Math.round((100 * job.done) / job.total) : 0;
  });

  private polling?: Subscription;

  constructor() {
    this.loadDatasets();
    this.selectDataset('golden');
    // Une execution lancee avant un rechargement de la page continue cote Gateway : on la reprend.
    this.api.runs().subscribe((jobs) => {
      const active = jobs.find((j) => j.status === 'RUNNING');
      if (active) {
        this.track(active);
      }
    });
  }

  protected selectDataset(id: string): void {
    this.datasetId.set(id);
    this.category.set('');
    this.cases.set([]);
    this.api.datasetCases(id).subscribe({ next: (cases) => this.cases.set(cases), error: () => this.cases.set([]) });
  }

  protected launch(): void {
    const request: RunRequest = {
      datasetId: this.datasetId(),
      provider: this.provider() || undefined,
      model: this.model().trim() || undefined,
      caseIds: this.category() ? this.scope().map((c) => c.id) : undefined,
      useJudge: this.useJudge(),
      judgeProvider: this.useJudge() ? this.judgeProvider() || undefined : undefined,
      judgeModel: this.useJudge() ? this.judgeModel().trim() || undefined : undefined,
      temperature: toNumber(this.temperature()),
      maxTokens: toNumber(this.maxTokens()),
      allowFallback: this.allowFallback(),
      systemPrompt: this.systemPrompt().trim() || undefined,
    };
    this.startError.set(null);
    this.api.startRun(request).subscribe({
      next: (job) => this.track(job),
      error: (error: HttpErrorResponse) => this.startError.set(error.error?.message ?? 'Lancement impossible'),
    });
  }

  protected cancel(): void {
    const job = this.job();
    if (job) {
      this.api.cancelRun(job.id).subscribe((updated) => this.job.set(updated));
    }
  }

  protected dismiss(): void {
    this.job.set(null);
  }

  protected onFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    this.importError.set(null);
    this.importName.set(slug(file.name.replace(/\.json$/i, '')));
    file.text().then((text) => this.importJson.set(text));
  }

  protected confirmImport(): void {
    const json = this.importJson();
    if (!json) {
      return;
    }
    this.api.importDataset(this.importName(), json).subscribe({
      next: (summary) => {
        this.importJson.set(null);
        this.loadDatasets();
        this.selectDataset(summary.id);
      },
      error: (error: HttpErrorResponse) => this.importError.set(error.error?.message ?? 'Import impossible'),
    });
  }

  protected cancelImport(): void {
    this.importJson.set(null);
    this.importError.set(null);
  }

  private loadDatasets(): void {
    this.api.datasets().subscribe((datasets) => this.datasets.set(datasets));
  }

  private track(job: JobView): void {
    this.job.set(job);
    this.polling?.unsubscribe();
    if (job.status !== 'RUNNING') {
      return;
    }
    this.polling = interval(POLL_MS)
      .pipe(
        switchMap(() => this.api.run(job.id)),
        takeWhile((j) => j.status === 'RUNNING', true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((latest) => {
        this.now.set(Date.now());
        this.job.set(latest);
        if (latest.status === 'DONE' && latest.reportId) {
          this.finished.emit(latest.reportId);
        }
      });
  }
}

function toNumber(value: string): number | undefined {
  const trimmed = value.trim().replace(',', '.');
  return trimmed === '' || !Number.isFinite(Number(trimmed)) ? undefined : Number(trimmed);
}

/** Meme regle que le Gateway pour un nom de dataset : minuscules, chiffres, - et _. */
function slug(name: string): string {
  return name
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48);
}

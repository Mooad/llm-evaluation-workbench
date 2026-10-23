import { Component, computed, inject, signal } from '@angular/core';
import { formatCost, formatCount, formatDateTime } from '../core/format';
import { RagApi } from '../core/rag-api';
import { ModelChoice, RagEvalReport, RagEvalReportSummary, modelLabel, pct, summarize } from '../core/rag-eval';
import { RagLibrary } from '../core/rag-library';
import { RagError, RetrievalScope } from '../core/rag.models';
import { SessionSettings } from '../core/session-settings';
import { GatewayConfig } from '../core/gateway-config';
import { RagEvalCases } from './rag-eval-cases';
import { RagEvalRunner } from './rag-eval-runner';

/** Un indicateur, sa valeur et son ecart avec le rapport de comparaison. */
interface Kpi {
  label: string;
  help: string;
  value: number | null;
  baseline: number | null;
}

@Component({
  selector: 'app-rag-eval',
  imports: [RagEvalCases],
  templateUrl: './rag-eval.html',
  styleUrl: './rag-eval.scss',
})
export class RagEval {
  private readonly api = inject(RagApi);
  protected readonly runner = inject(RagEvalRunner);
  protected readonly session = inject(SessionSettings);
  protected readonly config = inject(GatewayConfig);
  protected readonly library = inject(RagLibrary);
  protected readonly modelLabel = modelLabel;
  /** Valeur du choix "juge = meme modele que la generation". */
  protected readonly SAME = '__same__';

  protected readonly pct = pct;
  protected readonly formatDateTime = formatDateTime;
  protected readonly formatCount = formatCount;
  protected readonly formatCost = formatCost;

  // Formulaire de lancement.
  protected readonly label = signal('');
  protected readonly topK = signal(5);
  protected readonly scope = signal<RetrievalScope>('ALL');
  protected readonly withGeneration = signal(true);
  /** '' = l'index par defaut de llm-rag. */
  protected readonly index = signal('');
  // Par defaut, le modele de la session (Playground, RAG Chat) : on evalue ce qu'on utilise.
  protected readonly genProvider = signal(this.session.provider());
  protected readonly genModel = signal(this.session.model());
  protected readonly judgeProvider = signal(this.SAME);
  protected readonly judgeModel = signal('');

  protected readonly reports = signal<RagEvalReportSummary[]>([]);
  protected readonly listError = signal<RagError | null>(null);
  protected readonly selected = signal<RagEvalReport | null>(null);
  protected readonly baselineId = signal<string>('');

  protected readonly baseline = computed(
    () => this.reports().find((r) => r.id === this.baselineId() && r.id !== this.selected()?.id) ?? null,
  );

  protected readonly summary = computed(() => {
    const report = this.selected();
    return report ? summarize(report, this.baseline()) : [];
  });


  protected readonly retrievalKpis = computed<Kpi[]>(() => {
    const r = this.selected()?.retrieval.metrics;
    const b = this.baseline()?.retrieval;
    const k = this.selected()?.topK ?? 5;
    if (!r) {
      return [];
    }
    return [
      { label: 'Bonne page en tête', help: 'Recall@1 : la page qui contient la réponse est le premier extrait.', value: r.recallAt1, baseline: b?.recallAt1 ?? null },
      { label: `Bonne page dans le top ${k}`, help: `Recall@${k} : la page qui contient la réponse fait partie des ${k} extraits envoyés au modèle. Sinon, le modèle ne peut pas bien répondre.`, value: r.recallAtK, baseline: b?.recallAtK ?? null },
      { label: 'Variantes couvertes', help: 'Questions à plusieurs variantes (deux versions d’un produit) dont chaque variante est retrouvée.', value: r.variantCoverage, baseline: b?.variantCoverage ?? null },
      { label: 'Extraits du bon document', help: 'Part des extraits envoyés qui viennent du document attendu. Le reste est du bruit.', value: r.documentPrecision, baseline: b?.documentPrecision ?? null },
    ];
  });

  protected readonly generationKpis = computed<Kpi[]>(() => {
    const g = this.selected()?.generation?.metrics;
    const b = this.baseline()?.generation;
    if (!g) {
      return [];
    }
    return [
      { label: 'Faits exacts — avec RAG', help: 'La réponse contient tous les faits attendus (vérifié par règle, sans LLM).', value: g.ragAccuracy, baseline: b?.ragAccuracy ?? null },
      { label: 'Faits exacts — modèle seul', help: 'Même question, sans aucun document : ce que le modèle sait par lui-même.', value: g.llmAccuracy, baseline: b?.llmAccuracy ?? null },
      { label: 'Fidélité aux extraits', help: 'Selon le LLM juge : la réponse RAG n’invente rien qui ne soit dans les extraits.', value: g.ragFaithfulness, baseline: b?.ragFaithfulness ?? null },
      { label: 'Abstention hors corpus', help: 'Questions dont la réponse n’est dans aucun document : le RAG dit qu’il ne sait pas.', value: g.ragAbstention, baseline: b?.ragAbstention ?? null },
    ];
  });

  constructor() {
    this.refresh();
  }

  protected refresh(selectId?: string): void {
    this.api.reports<RagEvalReportSummary>().subscribe({
      next: (reports) => {
        this.reports.set(reports);
        this.listError.set(null);
        const id = selectId ?? this.selected()?.id ?? reports[0]?.id;
        if (id) {
          this.open(id);
        }
      },
      error: (error: RagError) => this.listError.set(error),
    });
  }

  protected open(id: string): void {
    this.api.report<RagEvalReport>(id).subscribe({
      next: (report) => {
        this.selected.set(withDefaults(report));
        // Par defaut, on compare au rapport precedent : c'est la question naturelle ("ai-je progresse ?").
        if (!this.baselineId() || this.baselineId() === id) {
          const index = this.reports().findIndex((r) => r.id === id);
          this.baselineId.set(this.reports()[index + 1]?.id ?? '');
        }
      },
      error: (error: RagError) => this.listError.set(error),
    });
  }

  protected async launch(): Promise<void> {
    const model: ModelChoice = { provider: this.genProvider() || null, model: this.genModel().trim() || null };
    const judge: ModelChoice | null =
      this.judgeProvider() === this.SAME
        ? null
        : { provider: this.judgeProvider() || null, model: this.judgeModel().trim() || null };
    const report = await this.runner.run({
      label: this.label(),
      topK: this.topK(),
      scope: this.scope(),
      index: this.index(),
      generation: this.withGeneration(),
      model,
      judge,
    });
    if (report?.id) {
      this.label.set('');
      this.baselineId.set(this.selected()?.id ?? '');
      this.refresh(report.id);
    }
  }

  protected round(value: number | null): number | null {
    return value === null ? null : Math.round(value);
  }

  protected setTopK(input: HTMLInputElement): void {
    const value = Math.min(20, Math.max(1, Math.round(Number(input.value) || 5)));
    this.topK.set(value);
    input.value = String(value);
  }

  /** Ecart en points, signe : "+3,1 pts". Rien si l'une des deux valeurs manque. */
  protected delta(kpi: Kpi): string | null {
    if (kpi.value === null || kpi.baseline === null) {
      return null;
    }
    const points = (kpi.value - kpi.baseline) * 100;
    if (Math.abs(points) < 0.05) {
      return '= identique';
    }
    const value = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 }).format(Math.abs(points));
    return `${points > 0 ? '+' : '−'}${value} pts`;
  }

  protected deltaClass(kpi: Kpi): string {
    if (kpi.value === null || kpi.baseline === null || Math.abs(kpi.value - kpi.baseline) < 0.0005) {
      return '';
    }
    return kpi.value > kpi.baseline ? 'up' : 'down';
  }
}

/**
 * Un rapport enregistre sans ces champs (mesure de recherche lancee hors du portail) les a dans
 * sa partie recherche : on les reprend de la, plutot que d'afficher une date vide ou une portee fausse.
 */
function withDefaults(report: RagEvalReport): RagEvalReport {
  return {
    ...report,
    runAt: report.runAt ?? report.retrieval.runAt,
    topK: report.topK ?? report.retrieval.topK,
    scope: report.scope ?? report.retrieval.scope,
    provider: report.provider ?? null,
    model: report.model ?? null,
    index: report.index ?? report.retrieval.index.name ?? null,
    // Rapports anterieurs au choix du juge : generation et juge etaient le meme modele.
    generationModel: report.generationModel ?? (report.generation ? { provider: report.provider ?? null, model: report.model ?? null } : null),
    judgeModel: report.judgeModel ?? (report.generation ? { provider: report.provider ?? null, model: report.model ?? null } : null),
    generation: report.generation ?? null,
  };
}

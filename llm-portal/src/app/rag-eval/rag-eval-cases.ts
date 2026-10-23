import { Component, computed, input, signal } from '@angular/core';
import { GenerationCase, RagEvalReport } from '../core/rag-eval';
import { locationLabel } from '../core/rag-prompt';
import { RetrievalCase, SearchHit } from '../core/rag.models';

interface Row {
  retrieval: RetrievalCase;
  generation: GenerationCase | null;
}

type Filter = 'all' | 'problems';

/** Une ligne par question : ce que la recherche a ramene, ce que les deux modes ont repondu. */
@Component({
  selector: 'app-rag-eval-cases',
  templateUrl: './rag-eval-cases.html',
  styleUrl: './rag-eval-cases.scss',
})
export class RagEvalCases {
  readonly report = input.required<RagEvalReport>();

  protected readonly locationLabel = locationLabel;
  protected readonly filter = signal<Filter>('all');

  protected readonly rows = computed<Row[]>(() => {
    const generation = new Map((this.report().generation?.cases ?? []).map((c) => [c.id, c]));
    const rows = this.report().retrieval.cases.map((c) => ({ retrieval: c, generation: generation.get(c.id) ?? null }));
    return this.filter() === 'all' ? rows : rows.filter((row) => this.isProblem(row));
  });

  /** Ce qui merite d'etre lu : bonne page absente ou pas en tete, faits manquants, invention, abstention ratee. */
  protected isProblem(row: Row): boolean {
    const { retrieval: r, generation: g } = row;
    if (r.status === 'OK' && r.rank !== 1) {
      return true;
    }
    if (r.variantsCovered === false) {
      return true;
    }
    const rag = g?.rag;
    if (!rag) {
      return false;
    }
    if (rag.error || rag.factsMissing.length > 0 || rag.judge?.fidele === false) {
      return r.status === 'OK' || rag.judge?.fidele === false;
    }
    return r.status === 'OUT_OF_CORPUS' && rag.judge?.abstention === false;
  }

  /** L'extrait couvre-t-il une page attendue ? Un extrait par section peut couvrir plusieurs pages. */
  protected isExpected(hit: SearchHit, c: RetrievalCase): boolean {
    if (hit.fileName !== c.document) {
      return false;
    }
    const location = hit.location;
    if (location.type === 'page') {
      return c.pages.includes(location.page);
    }
    return location.type === 'pageRange' && c.pages.some((p) => p >= location.from && p <= location.to);
  }

  protected rankLabel(c: RetrievalCase): string {
    switch (c.status) {
      case 'OUT_OF_CORPUS':
        return 'hors corpus';
      case 'DOCUMENT_ABSENT':
        return 'document non indexé';
      default:
        return c.rank === null ? 'absente' : c.rank === 1 ? '1re' : `${c.rank}e`;
    }
  }

  protected rankClass(c: RetrievalCase): string {
    if (c.status !== 'OK') {
      return 'verdict';
    }
    return c.rank === 1 ? 'verdict verdict--pass' : c.rank === null ? 'verdict verdict--fail' : 'verdict verdict--error';
  }
}

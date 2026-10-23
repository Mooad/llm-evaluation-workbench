import { Injectable, computed, effect, signal } from '@angular/core';

const STORAGE_KEY = 'llm-portal.rag-settings';

export const DEFAULT_TOP_K = 5;
export const MAX_TOP_K = 20;

interface StoredRagSettings {
  ragMode: boolean;
  topK: number;
  cite: boolean;
  showSources: boolean;
  /** Collection interrogee ; '' = toutes. */
  collection: string;
  /** Index (strategie de decoupage) interroge ; '' = celui par defaut. */
  index: string;
  /** Documents decoches. On garde les exclus plutot que les inclus : un nouveau document est actif d'office. */
  excludedIds: string[];
}

const DEFAULTS: StoredRagSettings = {
  ragMode: true,
  topK: DEFAULT_TOP_K,
  cite: true,
  showSources: true,
  collection: '',
  index: '',
  excludedIds: [],
};

/**
 * Reglages de l'onglet RAG Chat, conserves au rechargement et oublies a la fermeture
 * de l'onglet (sessionStorage), comme les reglages de session du Playground.
 */
@Injectable({ providedIn: 'root' })
export class RagSettings {
  private readonly initial = restore();

  /** false = "LLM seul" : meme question, sans recherche, pour comparer. */
  readonly ragMode = signal(this.initial.ragMode);
  readonly topK = signal(this.initial.topK);
  readonly cite = signal(this.initial.cite);
  readonly showSources = signal(this.initial.showSources);
  /** Restreint la recherche a un groupe de documents ; le filtre est applique par llm-rag (Qdrant). */
  readonly collection = signal(this.initial.collection);
  /** A, B... : meme documents, decoupes autrement. '' = l'index par defaut de llm-rag. */
  readonly index = signal(this.initial.index);
  readonly excludedIds = signal<string[]>(this.initial.excludedIds);

  private readonly snapshot = computed<StoredRagSettings>(() => ({
    ragMode: this.ragMode(),
    topK: this.topK(),
    cite: this.cite(),
    showSources: this.showSources(),
    collection: this.collection(),
    index: this.index(),
    excludedIds: this.excludedIds(),
  }));

  constructor() {
    effect(() => persist(this.snapshot()));
  }

  /** Borne la saisie : llm-rag refuse au-dela de 50, et au-dela de 20 le prompt grossit pour peu de gain. */
  setTopK(value: number): void {
    if (Number.isFinite(value)) {
      this.topK.set(Math.min(MAX_TOP_K, Math.max(1, Math.round(value))));
    }
  }

  isIncluded(documentId: string): boolean {
    return !this.excludedIds().includes(documentId);
  }

  toggleDocument(documentId: string): void {
    this.excludedIds.update((ids) =>
      ids.includes(documentId) ? ids.filter((id) => id !== documentId) : [...ids, documentId],
    );
  }
}

// Le stockage peut etre indisponible (navigation privee, donnees bloquees) :
// la page doit fonctionner quand meme, sans memoire de session.
function restore(): StoredRagSettings {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? { ...DEFAULTS, ...JSON.parse(raw) } : DEFAULTS;
  } catch {
    return DEFAULTS;
  }
}

function persist(settings: StoredRagSettings): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
  } catch {
    // voir restore()
  }
}

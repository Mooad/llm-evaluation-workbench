import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, Subscription, map, of, switchMap, tap } from 'rxjs';
import { GatewayApi } from '../core/gateway-api';
import { ChatMessage, ChatRequest, GatewayError, StreamSummary } from '../core/gateway.models';
import { RagApi } from '../core/rag-api';
import { RagLibrary } from '../core/rag-library';
import { buildRagMessages } from '../core/rag-prompt';
import { RagError, SearchHit } from '../core/rag.models';
import { RagSettings } from '../core/rag-settings';
import { SessionSettings } from '../core/session-settings';

export type TurnStatus = 'searching' | 'streaming' | 'done' | 'stopped' | 'error';

export interface RagTurn {
  id: number;
  role: 'user' | 'assistant';
  content: string;
  status?: TurnStatus;
  /** rag = recherche puis generation ; llm = question seule, pour comparer. */
  mode?: 'rag' | 'llm';
  hits?: SearchHit[];
  /** Mesure par le navigateur : aller-retour de POST /search (embedding de la question compris). */
  searchMs?: number;
  summary?: StreamSummary;
  error?: GatewayError | RagError;
}

/**
 * Une conversation RAG : pour chaque question, recherche dans llm-rag, puis generation par
 * le Gateway (/chat/stream) avec les extraits retrouves.
 *
 * Service racine : la conversation survit a un changement d'onglet du portail.
 */
@Injectable({ providedIn: 'root' })
export class RagConversation {
  private readonly gateway = inject(GatewayApi);
  private readonly rag = inject(RagApi);
  private readonly library = inject(RagLibrary);
  private readonly settings = inject(RagSettings);
  private readonly session = inject(SessionSettings);

  readonly turns = signal<RagTurn[]>([]);

  readonly busy = computed(() => this.turns().some((t) => t.status === 'searching' || t.status === 'streaming'));

  /** Extraits du dernier tour qui en a : ce que le panneau "Sources retrouvees" affiche. */
  readonly lastHits = computed(() => [...this.turns()].reverse().find((t) => t.hits)?.hits ?? null);

  readonly lastSummary = computed(() => [...this.turns()].reverse().find((t) => t.summary)?.summary ?? null);

  readonly totals = computed(() => {
    const summaries = this.turns()
      .map((t) => t.summary)
      .filter((s): s is StreamSummary => !!s);
    const known = summaries.filter((s) => s.estimatedCost !== null);
    return {
      requests: summaries.length,
      cost: known.reduce((sum, s) => sum + (s.estimatedCost ?? 0), 0),
      unknownCost: summaries.length - known.length,
      currency: summaries[0]?.currency ?? 'USD',
    };
  });

  /** Pourquoi on ne peut pas envoyer en mode RAG ; null = rien ne bloque. */
  readonly blocker = computed(() =>
    this.settings.ragMode() && this.library.activeDocuments().length === 0
      ? 'Ajoute un document prêt (ou coche-le), ou désactive le mode RAG.'
      : null,
  );

  private nextId = 0;
  private subscription?: Subscription;

  send(text: string): boolean {
    const question = text.trim();
    if (!question || this.busy() || this.blocker()) {
      return false;
    }

    const ragMode = this.settings.ragMode();
    const history = this.history();
    const user: RagTurn = { id: this.nextId++, role: 'user', content: question };
    const assistant: RagTurn = {
      id: this.nextId++,
      role: 'assistant',
      content: '',
      mode: ragMode ? 'rag' : 'llm',
      status: ragMode ? 'searching' : 'streaming',
    };
    this.turns.update((turns) => [...turns, user, assistant]);

    const hits$: Observable<SearchHit[] | null> = ragMode ? this.search(question, assistant.id) : of(null);

    this.subscription = hits$
      .pipe(
        switchMap((hits) =>
          this.gateway.stream(this.request(history, question, hits)),
        ),
      )
      .subscribe({
        next: (event) => {
          switch (event.type) {
            case 'delta':
              this.patch(assistant.id, (t) => ({ content: t.content + event.content }));
              break;
            case 'done':
              this.patch(assistant.id, () => ({ status: 'done', summary: event.summary }));
              break;
            case 'error':
              this.patch(assistant.id, () => ({ status: 'error', error: event.error }));
              break;
          }
        },
        error: (error: unknown) => this.patch(assistant.id, () => ({ status: 'error', error: asError(error) })),
        complete: () =>
          // Flux ferme sans "done" ni "error" : coupure cote serveur.
          this.patch(assistant.id, (t) => (t.status === 'streaming' ? { status: 'stopped' } : {})),
      });
    return true;
  }

  stop(): void {
    this.subscription?.unsubscribe();
    this.turns.update((turns) =>
      turns.map((t) => (t.status === 'searching' || t.status === 'streaming' ? { ...t, status: 'stopped' } : t)),
    );
  }

  clear(): void {
    this.stop();
    this.turns.set([]);
  }

  private search(question: string, turnId: number): Observable<SearchHit[]> {
    const startedAt = performance.now();
    return this.rag
      .search({
        query: question,
        documentIds: this.library.activeDocuments().map((d) => d.documentId),
        topK: this.settings.topK(),
        // Redondant avec documentIds, qui sont deja ceux de la collection : c'est voulu, llm-rag
        // applique le filtre lui-meme, sans dependre de la liste que le portail croit a jour.
        collections: this.settings.collection() ? [this.settings.collection()] : [],
        index: this.library.selectedIndex()?.name,
      })
      .pipe(
        map((response) => response.results),
        tap((hits) =>
          this.patch(turnId, () => ({ hits, searchMs: performance.now() - startedAt, status: 'streaming' })),
        ),
      );
  }

  private request(history: ChatMessage[], question: string, hits: SearchHit[] | null): ChatRequest {
    const messages =
      hits === null
        ? [...history, { role: 'user' as const, content: question }]
        : buildRagMessages(history, question, hits, { cite: this.settings.cite() });
    // Fournisseur, modele, temperature : les reglages de session du Playground s'appliquent ici aussi.
    return { ...this.session.requestOptions(), messages };
  }

  /** Sans memoire cote Gateway : questions et reponses abouties, sans les extraits des tours passes. */
  private history(): ChatMessage[] {
    return this.turns()
      .filter((t) => t.role === 'user' || (t.status === 'done' && t.content))
      .map((t) => ({ role: t.role, content: t.content }));
  }

  private patch(id: number, change: (turn: RagTurn) => Partial<RagTurn>): void {
    this.turns.update((turns) => turns.map((t) => (t.id === id ? { ...t, ...change(t) } : t)));
  }
}

function asError(error: unknown): GatewayError | RagError {
  if (error && typeof error === 'object' && 'error' in error && 'message' in error) {
    return error as RagError;
  }
  return { error: 'network_error', message: String(error) };
}

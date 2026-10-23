import {
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { GatewayApi } from '../core/gateway-api';
import { GatewayConfig } from '../core/gateway-config';
import { ChatMessage, ChatRequest, GatewayError, StreamSummary } from '../core/gateway.models';
import {
  formatCost,
  formatCount,
  formatMs,
  formatReasoning,
  originLabel,
} from '../core/format';
import { ReasoningChoice, SessionSettings } from '../core/session-settings';

interface Turn {
  id: number;
  role: 'user' | 'assistant';
  content: string;
  status?: 'streaming' | 'done' | 'stopped' | 'error';
  summary?: StreamSummary;
  error?: GatewayError;
  /** Mesure par le navigateur : envoi -> premier fragment affiche (reseau et proxy compris). */
  browserFirstTokenMs?: number;
}

const EXAMPLES = [
  "Explique en une phrase ce qu'est un fichier VTT.",
  'Compare H.264 et AV1 en trois points.',
  'Écris un haïku sur Kubernetes.',
];

@Component({
  selector: 'app-playground',
  imports: [RouterLink],
  templateUrl: './playground.html',
  styleUrl: './playground.scss',
})
export class Playground {
  private readonly api = inject(GatewayApi);
  private readonly transcript = viewChild<ElementRef<HTMLElement>>('transcript');

  protected readonly config = inject(GatewayConfig);
  protected readonly session = inject(SessionSettings);

  protected readonly examples = EXAMPLES;
  protected readonly formatCost = formatCost;
  protected readonly formatMs = formatMs;
  protected readonly formatCount = formatCount;
  protected readonly formatReasoning = formatReasoning;
  protected readonly originLabel = originLabel;

  /** Le fournisseur que la prochaine requete visera : celui de la session, sinon le defaut du Gateway. */
  protected readonly targetProvider = computed(
    () => this.session.provider() || this.config.providers()?.default || '',
  );

  /** Ce qui s'appliquera si un champ de session reste vide. */
  protected readonly globalDefaults = computed(() => this.config.defaultsFor(this.targetProvider()));

  protected readonly draft = signal('');
  protected readonly turns = signal<Turn[]>([]);

  protected readonly streaming = computed(() => this.turns().some((t) => t.status === 'streaming'));

  protected readonly lastResponse = computed(() =>
    [...this.turns()].reverse().find((t) => t.role === 'assistant' && (t.summary || t.error)),
  );

  protected readonly totals = computed(() => {
    const summaries = this.turns()
      .map((t) => t.summary)
      .filter((s): s is StreamSummary => !!s);
    const known = summaries.filter((s) => s.estimatedCost !== null);
    return {
      requests: summaries.length,
      cost: known.reduce((sum, s) => sum + (s.estimatedCost ?? 0), 0),
      unknownCost: summaries.length - known.length,
      currency: summaries[0]?.currency ?? 'USD',
      inputTokens: summaries.reduce((sum, s) => sum + (s.inputTokens ?? 0), 0),
      outputTokens: summaries.reduce((sum, s) => sum + (s.outputTokens ?? 0), 0),
    };
  });

  private nextId = 0;
  private subscription?: Subscription;
  private pinnedToBottom = true;

  constructor() {
    // Suit le texte qui arrive, sauf si l'utilisateur est remonte lire plus haut.
    afterRenderEffect(() => {
      this.turns();
      const el = this.transcript()?.nativeElement;
      if (el && this.pinnedToBottom) {
        el.scrollTop = el.scrollHeight;
      }
    });
  }

  /** Placeholder d'un champ vide : la valeur globale qui s'appliquera a sa place. */
  protected fallbackHint(value: number | null | undefined): string {
    return value === null || value === undefined ? 'défaut' : `global : ${value}`;
  }

  protected setReasoning(value: string): void {
    this.session.reasoning.set(value as ReasoningChoice);
  }

  protected onScroll(el: HTMLElement): void {
    this.pinnedToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
  }

  protected onComposerKey(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      this.send();
    }
  }

  protected send(text = this.draft()): void {
    const content = text.trim();
    if (!content || this.streaming()) {
      return;
    }

    const request = this.buildRequest(content);
    const user: Turn = { id: this.nextId++, role: 'user', content };
    const assistant: Turn = { id: this.nextId++, role: 'assistant', content: '', status: 'streaming' };
    this.turns.update((turns) => [...turns, user, assistant]);
    this.draft.set('');
    this.pinnedToBottom = true;

    const sentAt = performance.now();
    this.subscription = this.api.stream(request).subscribe({
      next: (event) => {
        switch (event.type) {
          case 'delta':
            this.patch(assistant.id, (t) => ({
              content: t.content + event.content,
              browserFirstTokenMs: t.browserFirstTokenMs ?? performance.now() - sentAt,
            }));
            break;
          case 'done':
            this.patch(assistant.id, () => ({ status: 'done', summary: event.summary }));
            break;
          case 'error':
            this.patch(assistant.id, () => ({ status: 'error', error: event.error }));
            break;
        }
      },
      error: (error: unknown) =>
        this.patch(assistant.id, () => ({
          status: 'error',
          error: { error: 'network_error', message: String(error) },
        })),
      complete: () =>
        // Flux ferme sans "done" ni "error" : coupure cote serveur.
        this.patch(assistant.id, (t) => (t.status === 'streaming' ? { status: 'stopped' } : {})),
    });
  }

  protected stop(): void {
    this.subscription?.unsubscribe();
    this.turns.update((turns) =>
      turns.map((t) => (t.status === 'streaming' ? { ...t, status: 'stopped' } : t)),
    );
  }

  /** Vide la conversation ; les reglages de session restent. */
  protected newConversation(): void {
    this.stop();
    this.turns.set([]);
  }

  /** Sans memoire cote Gateway : on renvoie tout l'historique utile a chaque message. */
  private buildRequest(content: string): ChatRequest {
    const history: ChatMessage[] = this.turns()
      .filter((t) => t.role === 'user' || (t.status === 'done' && t.content))
      .map((t) => ({ role: t.role, content: t.content }));

    const system = this.session.systemPrompt().trim();
    const messages: ChatMessage[] = [
      ...(system ? [{ role: 'system' as const, content: system }] : []),
      ...history,
      { role: 'user', content },
    ];

    return { ...this.session.requestOptions(), messages };
  }

  private patch(id: number, change: (turn: Turn) => Partial<Turn>): void {
    this.turns.update((turns) => turns.map((t) => (t.id === id ? { ...t, ...change(t) } : t)));
  }
}

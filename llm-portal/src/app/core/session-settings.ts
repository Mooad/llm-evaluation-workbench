import { Injectable, computed, effect, signal } from '@angular/core';
import { ChatRequest } from './gateway.models';

const STORAGE_KEY = 'llm-portal.session-settings';

/** '' = ne rien envoyer : le Gateway applique son defaut global. */
export type ReasoningChoice = '' | 'on' | 'off';

interface StoredSettings {
  provider: string;
  model: string;
  reasoning: ReasoningChoice;
  temperature: string;
  maxTokens: string;
  systemPrompt: string;
}

const EMPTY: StoredSettings = {
  provider: '',
  model: '',
  reasoning: '',
  temperature: '',
  maxTokens: '',
  systemPrompt: '',
};

/**
 * Reglages de la session du portail : valables pour toutes les conversations de l'onglet,
 * conserves au rechargement, oublies a sa fermeture (sessionStorage).
 *
 * Le Gateway etant sans memoire, ils voyagent dans chaque requete : c'est le portail,
 * pas le Gateway, qui se souvient de la session.
 */
@Injectable({ providedIn: 'root' })
export class SessionSettings {
  private readonly initial = restore();

  readonly provider = signal(this.initial.provider);
  readonly model = signal(this.initial.model);
  readonly reasoning = signal<ReasoningChoice>(this.initial.reasoning);
  readonly temperature = signal(this.initial.temperature);
  readonly maxTokens = signal(this.initial.maxTokens);
  readonly systemPrompt = signal(this.initial.systemPrompt);

  private readonly snapshot = computed<StoredSettings>(() => ({
    provider: this.provider(),
    model: this.model(),
    reasoning: this.reasoning(),
    temperature: this.temperature(),
    maxTokens: this.maxTokens(),
    systemPrompt: this.systemPrompt(),
  }));

  /** Vrai des qu'un reglage s'ecarte des valeurs globales. */
  readonly customized = computed(() =>
    Object.entries(this.snapshot()).some(([, value]) => value !== ''),
  );

  constructor() {
    effect(() => persist(this.snapshot()));
  }

  reset(): void {
    this.provider.set(EMPTY.provider);
    this.model.set(EMPTY.model);
    this.reasoning.set(EMPTY.reasoning);
    this.temperature.set(EMPTY.temperature);
    this.maxTokens.set(EMPTY.maxTokens);
    this.systemPrompt.set(EMPTY.systemPrompt);
  }

  /** Seuls les reglages renseignes partent : les autres restent au Gateway. */
  requestOptions(): Omit<ChatRequest, 'messages'> {
    const reasoning = this.reasoning();
    return {
      provider: this.provider() || undefined,
      model: this.model().trim() || undefined,
      temperature: toNumber(this.temperature()),
      maxTokens: toNumber(this.maxTokens()),
      reasoning: reasoning === '' ? undefined : reasoning === 'on',
    };
  }
}

function toNumber(value: string): number | undefined {
  const trimmed = value.trim().replace(',', '.');
  if (trimmed === '') {
    return undefined;
  }
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : undefined;
}

// Le stockage peut etre indisponible (navigation privee, donnees bloquees) :
// la page doit fonctionner quand meme, sans memoire de session.
function restore(): StoredSettings {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? { ...EMPTY, ...JSON.parse(raw) } : EMPTY;
  } catch {
    return EMPTY;
  }
}

function persist(settings: StoredSettings): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
  } catch {
    // voir restore()
  }
}

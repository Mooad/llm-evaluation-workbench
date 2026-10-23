/**
 * Contrat du LLM Gateway, tel qu'il passe sur le fil.
 * Source de verite : les DTO Java du Gateway (api/dto) — garder les deux alignes.
 */

export type Role = 'system' | 'user' | 'assistant';

export interface ChatMessage {
  role: Role;
  content: string;
}

/** Le Gateway est sans memoire : chaque requete porte tout l'historique. */
export interface ChatRequest {
  provider?: string;
  model?: string;
  messages: ChatMessage[];
  temperature?: number;
  maxTokens?: number;
  /** Mode raisonnement (DeepSeek). Absent = defaut global du Gateway. */
  reasoning?: boolean;
}

export type FinishReason = 'COMPLETE' | 'TRUNCATED' | 'REFUSED' | 'TOOL_USE' | 'UNKNOWN';

/** Evenement "done" de /chat/stream. */
export interface StreamSummary {
  provider: string;
  requestedProvider: string;
  fallbackUsed: boolean;
  model: string;
  inputTokens: number | null;
  outputTokens: number | null;
  /** null = tarif inconnu, jamais "gratuit". */
  estimatedCost: number | null;
  currency: string;
  /** Comment le cout a ete calcule, ou pourquoi il manque. */
  costBasis: string;
  /** Mesure par le Gateway : reception de la requete -> premier fragment. */
  firstTokenMs: number | null;
  /** Mesure par le Gateway : duree de l'appel au fournisseur qui a repondu. */
  latencyMs: number;
  finishReason: FinishReason;
  providerFinishReason: string | null;
  /** Reglages reellement envoyes au fournisseur, avec leur origine. */
  applied: AppliedOptions;
}

/** REQUEST = envoye par le client (ici : la session du portail). */
export type Origin = 'REQUEST' | 'GLOBAL' | 'PROVIDER';

export interface AppliedSetting<T> {
  value: T | null;
  origin: Origin;
}

export interface AppliedOptions {
  reasoning: AppliedSetting<boolean>;
  maxTokens: AppliedSetting<number>;
  temperature: AppliedSetting<number>;
  topP: number | null;
}

export interface GatewayError {
  error: string;
  message: string;
}

export type StreamEvent =
  | { type: 'delta'; content: string }
  | { type: 'done'; summary: StreamSummary }
  | { type: 'error'; error: GatewayError };

export interface ProvidersInfo {
  available: string[];
  default: string;
}

export type HealthStatus = 'UP' | 'DEGRADED' | 'DOWN' | 'UNKNOWN';

export interface ProviderHealth {
  provider: string;
  status: HealthStatus;
  defaultModel: string;
  modelAvailable: boolean | null;
  latencyMs: number | null;
  detail: string;
}

export interface ProvidersStatus {
  overall: 'UP' | 'DOWN';
  default: string;
  providers: ProviderHealth[];
}

/** null = ne rien imposer, le fournisseur garde son propre defaut. */
export interface GenerationDefaults {
  reasoning: boolean | null;
  maxTokens: number | null;
  temperature: number | null;
}

export interface ProviderSettings {
  provider: string;
  supportsReasoning: boolean;
  defaults: GenerationDefaults;
}

/** Defauts globaux du Gateway, stockes dans `file`. */
export interface SettingsView {
  file: string;
  providers: ProviderSettings[];
}

/** Prix pour un million de tokens. */
export interface ModelPrice {
  model: string;
  inputPer1m: number;
  outputPer1m: number;
}

export interface PricingView {
  currency: string;
  source: string;
  models: ModelPrice[];
}

/** Reponse de POST /chat (sans streaming) : utilisee par l'evaluation RAG, qui n'affiche rien en direct. */
export interface ChatResponse {
  content: string;
  provider: string;
  requestedProvider: string;
  fallbackUsed: boolean;
  model: string;
  inputTokens: number | null;
  outputTokens: number | null;
  estimatedCost: number | null;
  currency: string;
  latencyMs: number;
  finishReason: FinishReason;
}

/** POST /chat/structured : la reponse est validee par le Gateway contre le schema JSON fourni. */
export interface StructuredChatRequest {
  provider?: string;
  model?: string;
  messages: ChatMessage[];
  schema: object;
  temperature?: number;
  maxTokens?: number;
}

export interface StructuredChatResponse<T> {
  valid: boolean;
  data: T | null;
  violations: string[];
  raw: string;
  provider: string;
  model: string;
  inputTokens: number | null;
  outputTokens: number | null;
  latencyMs: number;
}

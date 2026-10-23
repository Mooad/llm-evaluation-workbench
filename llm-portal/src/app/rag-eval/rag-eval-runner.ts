import { Injectable, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { GatewayApi } from '../core/gateway-api';
import { ChatMessage, ChatRequest } from '../core/gateway.models';
import { RagApi } from '../core/rag-api';
import {
  AnswerResult,
  ModelChoice,
  GenerationCase,
  JUDGE_SCHEMA,
  JudgeVerdict,
  RagEvalReport,
  checkFacts,
  generationMetrics,
  judgeMessages,
} from '../core/rag-eval';
import { buildRagMessages } from '../core/rag-prompt';
import { RagError, RetrievalCase, RetrievalScope } from '../core/rag.models';

export interface RunOptions {
  label: string;
  topK: number;
  scope: RetrievalScope;
  /** Index (strategie de decoupage) a evaluer ; '' = celui par defaut de llm-rag. */
  index: string;
  /** false = recherche seule (une minute) ; true = aussi les reponses et le juge (plusieurs minutes). */
  generation: boolean;
  /** Modele qui repond, avec et sans RAG. */
  model: ModelChoice;
  /** Modele juge ; null = le meme que celui qui repond. */
  judge: ModelChoice | null;
}

export interface RunProgress {
  done: number;
  total: number;
  step: string;
}

/**
 * Lance une evaluation RAG complete et la fait enregistrer par llm-rag.
 *
 * Le portail orchestre parce qu'il est le seul a parler aux deux services : llm-rag mesure la
 * recherche (il ne genere rien), le Gateway genere et juge (il ne connait pas le RAG).
 * Les questions passent une a une : Ollama traite une requete a la fois, paralleliser ne
 * ferait qu'allonger chaque appel.
 */
@Injectable({ providedIn: 'root' })
export class RagEvalRunner {
  private readonly rag = inject(RagApi);
  private readonly gateway = inject(GatewayApi);

  readonly running = signal(false);
  readonly progress = signal<RunProgress | null>(null);
  readonly error = signal<string | null>(null);

  private cancelled = false;

  cancel(): void {
    this.cancelled = true;
  }

  /** @return le rapport enregistre, ou null si l'evaluation a ete annulee ou a echoue */
  async run(options: RunOptions): Promise<RagEvalReport | null> {
    this.running.set(true);
    this.error.set(null);
    this.cancelled = false;
    try {
      this.progress.set({ done: 0, total: 1, step: 'Recherche : une requête par question…' });
      const retrieval = await firstValueFrom(this.rag.evaluateRetrieval(options.topK, options.scope, options.index));
      const judge = options.judge ?? options.model;

      let generation: RagEvalReport['generation'] = null;
      if (options.generation) {
        const todo = retrieval.cases.filter((c) => c.status !== 'DOCUMENT_ABSENT');
        const cases: GenerationCase[] = [];
        for (const [i, c] of todo.entries()) {
          if (this.cancelled) {
            return null;
          }
          cases.push(await this.evaluateCase(c, i, todo.length, options.model, judge));
        }
        generation = { metrics: generationMetrics(retrieval.cases, cases), cases };
      }

      const report: RagEvalReport = {
        label: options.label.trim() || defaultLabel(options, retrieval.index.name ?? options.index),
        runAt: new Date().toISOString(),
        topK: options.topK,
        scope: options.scope,
        index: retrieval.index.name ?? (options.index || null),
        provider: options.model.provider,
        model: options.model.model,
        generationModel: options.generation ? options.model : null,
        judgeModel: options.generation ? judge : null,
        retrieval,
        generation,
      };
      this.progress.set({ done: 1, total: 1, step: 'Enregistrement du rapport…' });
      return await firstValueFrom(this.rag.saveReport(report));
    } catch (e) {
      this.error.set(describe(e));
      return null;
    } finally {
      this.running.set(false);
      this.progress.set(null);
    }
  }

  private async evaluateCase(c: RetrievalCase, index: number, total: number, model: ModelChoice,
                             judge: ModelChoice): Promise<GenerationCase> {
    const step = (what: string) =>
      this.progress.set({ done: index, total, step: `Question ${index + 1}/${total} — ${what}` });

    step('réponse avec RAG');
    const rag = await this.answer(buildRagMessages([], c.question, c.hits, { cite: true }), c.facts, model);
    if (rag && !rag.error) {
      step('juge');
      rag.judge = await this.judge(c, rag.answer, judge);
    }
    step('réponse du modèle seul');
    const llm = await this.answer([{ role: 'user', content: c.question }], c.facts, model);
    return { id: c.id, rag, llm };
  }

  /** Une erreur d'appel est gardee sur la reponse : une question qui echoue ne doit pas arreter les autres. */
  private async answer(messages: ChatMessage[], facts: string[], model: ModelChoice): Promise<AnswerResult> {
    try {
      const response = await firstValueFrom(this.gateway.chat(this.request(messages, model)));
      const { found, missing } = checkFacts(response.content, facts);
      return {
        answer: response.content,
        factsFound: found,
        factsMissing: missing,
        inputTokens: response.inputTokens,
        outputTokens: response.outputTokens,
        cost: response.estimatedCost,
        currency: response.currency,
        latencyMs: response.latencyMs,
        judge: null,
        error: null,
      };
    } catch (e) {
      return {
        answer: '',
        factsFound: [],
        factsMissing: facts,
        inputTokens: null,
        outputTokens: null,
        cost: null,
        currency: 'USD',
        latencyMs: null,
        judge: null,
        error: describe(e),
      };
    }
  }

  private async judge(c: RetrievalCase, answer: string, judge: ModelChoice): Promise<JudgeVerdict | null> {
    try {
      const response = await firstValueFrom(
        this.gateway.structured<JudgeVerdict>({
          ...this.request(judgeMessages(c.question, c.hits, answer), judge),
          schema: JUDGE_SCHEMA,
          maxTokens: 400,
        }),
      );
      return response.valid ? response.data : null;
    } catch {
      // Juge indisponible ou sortie non conforme (422) : la question reste notee par regle.
      return null;
    }
  }

  /** Temperature 0 pour que deux executions comparent le systeme, pas le hasard. */
  private request(messages: ChatMessage[], target: ModelChoice): ChatRequest {
    return {
      provider: target.provider ?? undefined,
      model: target.model ?? undefined,
      messages,
      temperature: 0,
    };
  }
}

function defaultLabel(options: RunOptions, index: string): string {
  const scope = options.scope === 'COLLECTION' ? 'par collection' : 'tous documents';
  return `Index ${index || 'défaut'} · ${options.generation ? 'complète' : 'recherche seule'} · top ${options.topK} · ${scope}`;
}

function describe(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    return e.error?.message ?? `${e.status} ${e.statusText}`;
  }
  if (e && typeof e === 'object' && 'message' in e) {
    return String((e as RagError).message || (e as RagError).error);
  }
  return String(e);
}

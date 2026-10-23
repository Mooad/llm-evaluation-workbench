import { ChatMessage } from './gateway.models';
import { locationLabel } from './rag-prompt';
import { RetrievalCase, RetrievalMetrics, RetrievalReport, RetrievalScope, SearchHit } from './rag.models';

/**
 * Evaluation RAG, partie generation : notation des reponses et resume en clair.
 *
 * Deux notations, separees parce qu'elles ne mesurent pas la meme chose :
 * - par regle, sans LLM : la reponse contient-elle les faits attendus ("45 jours") ?
 * - par un LLM juge : la reponse invente-t-elle quelque chose hors des extraits ? s'abstient-elle ?
 */

/** Verdict du juge, sortie JSON validee par le Gateway (/chat/structured). */
export interface JudgeVerdict {
  /** Chaque information de la reponse figure dans les extraits (ne pas savoir est fidele). */
  fidele: boolean;
  /** La reponse dit que l'information n'est pas dans les documents. */
  abstention: boolean;
  justification: string;
}

export interface AnswerResult {
  answer: string;
  factsFound: string[];
  factsMissing: string[];
  inputTokens: number | null;
  outputTokens: number | null;
  cost: number | null;
  currency: string;
  latencyMs: number | null;
  judge: JudgeVerdict | null;
  error: string | null;
}

export interface GenerationCase {
  id: string;
  /** Reponse avec les extraits retrouves. */
  rag: AnswerResult | null;
  /** Meme question, sans document : ce que le modele sait seul. */
  llm: AnswerResult | null;
}

/** Taux de 0 a 1 ; null quand aucune question ne permet de les calculer. */
export interface GenerationMetrics {
  answerable: number;
  outOfCorpus: number;
  /** Questions avec reponse dont tous les faits attendus sont dans la reponse. */
  ragAccuracy: number | null;
  llmAccuracy: number | null;
  /** Reponses RAG jugees fideles aux extraits (rien d'invente). */
  ragFaithfulness: number | null;
  /** Questions hors corpus ou le RAG dit ne pas savoir : c'est le comportement attendu. */
  ragAbstention: number | null;
  /** Questions avec reponse ou le RAG dit pourtant ne pas savoir. */
  ragFalseAbstention: number | null;
  ragMeanInputTokens: number | null;
  llmMeanInputTokens: number | null;
  ragCost: number | null;
  llmCost: number | null;
  currency: string;
  ragMeanLatencyMs: number | null;
  llmMeanLatencyMs: number | null;
  errors: number;
}

/** Un modele vise par le Gateway ; null = son defaut (fournisseur par defaut, modele par defaut). */
export interface ModelChoice {
  provider: string | null;
  model: string | null;
}

/** Rapport complet, tel que stocke par llm-rag (POST /evaluations/reports). */
export interface RagEvalReport {
  id?: string;
  label: string;
  runAt: string;
  topK: number;
  scope: RetrievalScope;
  /** Index evalue (strategie de decoupage) ; absent dans les rapports anterieurs aux index. */
  index?: string | null;
  /** Modele qui genere les reponses (doublon de generationModel, garde pour les anciens rapports). */
  provider: string | null;
  model: string | null;
  generationModel?: ModelChoice | null;
  /** Modele juge ; un juge distinct evite qu'un modele note ses propres reponses. */
  judgeModel?: ModelChoice | null;
  retrieval: RetrievalReport;
  generation: { metrics: GenerationMetrics; cases: GenerationCase[] } | null;
}

/** Entree de GET /evaluations/reports : les metriques, sans le detail par question. */
export interface RagEvalReportSummary {
  id: string;
  runAt: string;
  label: string;
  topK: number;
  provider?: string | null;
  model?: string | null;
  retrieval: RetrievalMetrics;
  index: RetrievalReport['index'];
  generation: GenerationMetrics | null;
}

/** "deepseek · deepseek-flash", "défaut du Gateway" : pour l'en-tete d'un rapport. */
export function modelLabel(choice: ModelChoice | null | undefined): string {
  if (!choice || (!choice.provider && !choice.model)) {
    return 'défaut du Gateway';
  }
  return [choice.provider ?? 'défaut', choice.model].filter(Boolean).join(' · ');
}

// --- Faits attendus ---------------------------------------------------------------

/**
 * Minuscules, sans accents, espaces insecables remplaces, espaces multiples reduits :
 * "45 jours" doit se retrouver dans "45 Jours", "45 jours" ou "45  jours".
 */
export function normalizeText(text: string): string {
  return text
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toLowerCase()
    .replace(/[  ]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Un fait "a|b" est present si l'une de ses formes l'est. */
export function checkFacts(answer: string, facts: string[]): { found: string[]; missing: string[] } {
  const text = normalizeText(answer);
  const found: string[] = [];
  const missing: string[] = [];
  for (const fact of facts) {
    const present = fact.split('|').some((form) => text.includes(normalizeText(form)));
    (present ? found : missing).push(fact);
  }
  return { found, missing };
}

// --- Juge ---------------------------------------------------------------------------

export const JUDGE_SCHEMA = {
  type: 'object',
  properties: {
    fidele: { type: 'boolean' },
    abstention: { type: 'boolean' },
    justification: { type: 'string' },
  },
  required: ['fidele', 'abstention', 'justification'],
  additionalProperties: false,
};

/**
 * Le juge voit exactement ce que l'assistant a vu (les extraits), puis la reponse. Il ne note
 * pas l'exactitude, deja mesuree par regle, mais ce que les regles ne voient pas : une
 * information inventee, ou une reponse qui renonce.
 */
export function judgeMessages(question: string, hits: SearchHit[], answer: string): ChatMessage[] {
  const extracts =
    hits.length === 0
      ? '(aucun extrait)'
      : hits.map((hit, i) => `[${i + 1}] ${hit.fileName}, ${locationLabel(hit.location)}\n${hit.content}`).join('\n\n');
  return [
    {
      role: 'system',
      content:
        "Tu évalues la réponse d'un assistant qui doit répondre uniquement à partir d'extraits de documents. " +
        'Tu es strict et factuel. Tu réponds uniquement en JSON.',
    },
    {
      role: 'user',
      content:
        `Extraits fournis à l'assistant :\n\n${extracts}\n\nQuestion : ${question}\n\n` +
        `Réponse de l'assistant :\n"""\n${answer}\n"""\n\n` +
        'Indique :\n' +
        "- fidele : true si chaque information factuelle de la réponse figure dans les extraits (une réponse qui dit ne pas savoir est fidèle) ; false si elle contient au moins une information absente des extraits ou contraire à eux.\n" +
        "- abstention : true si la réponse dit que l'information n'est pas disponible dans les documents ; false sinon.\n" +
        '- justification : une phrase.',
    },
  ];
}

// --- Metriques ----------------------------------------------------------------------

export function generationMetrics(retrieval: RetrievalCase[], cases: GenerationCase[]): GenerationMetrics {
  const byId = new Map(cases.map((c) => [c.id, c]));
  const answerable = retrieval.filter((r) => r.status === 'OK').map((r) => byId.get(r.id)).filter(isCase);
  const outOfCorpus = retrieval.filter((r) => r.status === 'OUT_OF_CORPUS').map((r) => byId.get(r.id)).filter(isCase);
  const rag = cases.map((c) => c.rag).filter(isAnswer);
  const llm = cases.map((c) => c.llm).filter(isAnswer);
  const judged = rag.filter((a) => a.judge);

  return {
    answerable: answerable.length,
    outOfCorpus: outOfCorpus.length,
    ragAccuracy: rate(answerable, (c) => !!c.rag && !c.rag.error && c.rag.factsMissing.length === 0),
    llmAccuracy: rate(answerable, (c) => !!c.llm && !c.llm.error && c.llm.factsMissing.length === 0),
    ragFaithfulness: rate(judged, (a) => a.judge!.fidele),
    ragAbstention: rate(outOfCorpus.filter((c) => c.rag?.judge), (c) => c.rag!.judge!.abstention),
    ragFalseAbstention: rate(answerable.filter((c) => c.rag?.judge), (c) => c.rag!.judge!.abstention),
    ragMeanInputTokens: mean(rag.map((a) => a.inputTokens)),
    llmMeanInputTokens: mean(llm.map((a) => a.inputTokens)),
    ragCost: total(rag.map((a) => a.cost)),
    llmCost: total(llm.map((a) => a.cost)),
    currency: rag.find((a) => a.currency)?.currency ?? 'USD',
    ragMeanLatencyMs: mean(rag.map((a) => a.latencyMs)),
    llmMeanLatencyMs: mean(llm.map((a) => a.latencyMs)),
    errors: [...rag, ...llm].filter((a) => a.error).length,
  };
}

function isCase(c: GenerationCase | undefined): c is GenerationCase {
  return !!c;
}

function isAnswer(a: AnswerResult | null): a is AnswerResult {
  return !!a;
}

function rate<T>(items: T[], success: (item: T) => boolean): number | null {
  return items.length === 0 ? null : items.filter(success).length / items.length;
}

function mean(values: (number | null)[]): number | null {
  const known = values.filter((v): v is number => v !== null && v !== undefined);
  return known.length === 0 ? null : known.reduce((a, b) => a + b, 0) / known.length;
}

/** Cout total ; null des qu'un cout est inconnu, pour ne jamais afficher un total faux. */
function total(values: (number | null)[]): number | null {
  if (values.length === 0 || values.some((v) => v === null || v === undefined)) {
    return null;
  }
  return (values as number[]).reduce((a, b) => a + b, 0);
}

// --- Resume en clair ----------------------------------------------------------------

/**
 * Le rapport en quelques phrases, pour quelqu'un qui ne lit pas les metriques.
 * Chaque phrase dit ce qui est mesure et sur combien de questions.
 */
export function summarize(report: RagEvalReport, baseline?: RagEvalReportSummary | null): string[] {
  const r = report.retrieval.metrics;
  const n = r.evaluated;
  const lines: string[] = [];
  const k = report.topK;

  if (n > 0 && r.recallAt1 !== null && r.recallAtK !== null) {
    lines.push(
      `Recherche : la bonne page arrive en tête pour ${count(r.recallAt1, n)} questions sur ${n} (${pct(r.recallAt1)}), ` +
        `et fait partie des ${k} extraits envoyés au modèle pour ${count(r.recallAtK, n)} sur ${n} (${pct(r.recallAtK)}).`,
    );
  }
  if (r.variantCoverage !== null) {
    lines.push(
      `Quand la réponse existe en plusieurs variantes (deux modèles, deux équipements), toutes les variantes sont retrouvées dans ${pct(r.variantCoverage)} des cas.`,
    );
  }
  if (r.documentPrecision !== null) {
    lines.push(
      `${pct(r.documentPrecision)} des extraits envoyés au modèle viennent du bon document ; le reste est du bruit qu'il doit ignorer` +
        (report.scope === 'COLLECTION' ? ' (recherche limitée à la collection du document).' : '.'),
    );
  }
  if (r.meanTopScoreAnswerable !== null && r.meanTopScoreOutOfCorpus !== null) {
    const gap = r.meanTopScoreAnswerable - r.meanTopScoreOutOfCorpus;
    lines.push(
      `Questions hors corpus : meilleur score moyen ${score(r.meanTopScoreOutOfCorpus)}, contre ${score(r.meanTopScoreAnswerable)} pour les vraies questions. ` +
        (gap < 0.25
          ? "L'écart est trop faible pour qu'un seuil de score repère le hors-sujet : c'est au modèle de dire qu'il ne sait pas."
          : 'Un seuil de score pourrait écarter une partie du hors-sujet.'),
    );
  }

  const g = report.generation?.metrics;
  if (g) {
    if (g.ragAccuracy !== null && g.llmAccuracy !== null) {
      lines.push(
        `Réponses : avec RAG, la réponse contient les faits attendus pour ${count(g.ragAccuracy, g.answerable)} questions sur ${g.answerable} (${pct(g.ragAccuracy)}), ` +
          `contre ${count(g.llmAccuracy, g.answerable)} (${pct(g.llmAccuracy)}) pour le modèle seul, sans document.`,
      );
    }
    if (g.ragFaithfulness !== null) {
      lines.push(`Le juge estime ${pct(g.ragFaithfulness)} des réponses RAG fidèles aux extraits : rien d'inventé.`);
    }
    if (g.ragAbstention !== null) {
      lines.push(
        `Sur les ${g.outOfCorpus} questions dont la réponse n'est dans aucun document, le RAG dit ne pas savoir dans ${pct(g.ragAbstention)} des cas.`,
      );
    }
    if (g.ragFalseAbstention) {
      lines.push(`À l'inverse, il renonce à tort sur ${pct(g.ragFalseAbstention)} des questions qui avaient une réponse.`);
    }
    if (g.ragMeanInputTokens !== null && g.llmMeanInputTokens !== null) {
      lines.push(
        `Coût en contexte : ${Math.round(g.ragMeanInputTokens)} tokens d'entrée par question avec RAG, contre ${Math.round(g.llmMeanInputTokens)} sans ; ` +
          `c'est le prix des extraits, bien inférieur à l'envoi des documents entiers.`,
      );
    }
    if (g.errors > 0) {
      lines.push(`${g.errors} appels au modèle ont échoué : les questions concernées comptent comme fausses.`);
    }
  }

  if (baseline?.retrieval?.recallAtK !== null && baseline?.retrieval?.recallAtK !== undefined && r.recallAtK !== null) {
    lines.push(
      `Par rapport à « ${baseline.label} » : bonne page dans les ${k} extraits ${pct(baseline.retrieval.recallAtK)} → ${pct(r.recallAtK)}, ` +
        `extraits du bon document ${pct(baseline.retrieval.documentPrecision)} → ${pct(r.documentPrecision)}.`,
    );
  }
  return lines;
}

function count(rate: number, n: number): number {
  return Math.round(rate * n);
}

export function pct(value: number | null | undefined): string {
  return value === null || value === undefined
    ? '—'
    : `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 }).format(value * 100)} %`;
}

function score(value: number): string {
  return value.toFixed(2).replace('.', ',');
}

/**
 * Contrat d'evaluation du Gateway (/eval/...). Source de verite : les records Java du
 * package eval — garder les deux alignes.
 */

export type Dimension = 'CORRECTNESS' | 'FORMAT' | 'HALLUCINATION';
export type Verdict = 'PASS' | 'FAIL' | 'SKIPPED' | 'ERROR';

export const DIMENSIONS: Dimension[] = ['CORRECTNESS', 'FORMAT', 'HALLUCINATION'];

export interface DatasetSummary {
  id: string;
  builtIn: boolean;
  caseCount: number;
  categories: Record<string, number>;
  schemaCases: number;
  hash: string | null;
  error: string | null;
}

export interface EvalCase {
  id: string;
  category: string | null;
  prompt: string;
  schema?: unknown;
}

export interface DimensionStats {
  passed: number;
  failed: number;
  skipped: number;
  errors: number;
  /** passed / (passed + failed) : SKIPPED et ERROR n'y entrent pas. */
  passRate: number | null;
}

export interface LatencyStats {
  avgMs: number | null;
  p50Ms: number | null;
  maxMs: number | null;
}

export interface ReportSummary {
  id: string;
  runId: string;
  startedAt: string;
  durationMs: number;
  candidate: string;
  judge: string;
  datasetId: string;
  datasetHash: string | null;
  filtered: boolean;
  caseCount: number;
  dimensions: Partial<Record<Dimension, DimensionStats>>;
  latency: LatencyStats | null;
  totalCost: number | null;
  costPerCorrect: number | null;
  currency: string;
  schemaRate: number | null;
  warningCount: number;
}

export interface DimensionScore {
  dimension: Dimension;
  verdict: Verdict;
  method: string;
  reason: string;
}

export interface CaseResult {
  id: string;
  caseHash: string | null;
  category: string | null;
  prompt: string;
  answer: string | null;
  servedBy: string | null;
  fallbackUsed: boolean;
  model: string | null;
  latencyMs: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  cost: number | null;
  finishReason: string | null;
  schemaValid: boolean | null;
  scores: DimensionScore[];
  error: string | null;
}

export interface RunSettings {
  temperature: number;
  maxTokens: number;
  useJudge: boolean;
  allowFallback: boolean;
  filtered: boolean;
  /** Consigne envoyee en tete de chaque cas, null = aucune. */
  systemPrompt: string | null;
}

export interface EvalReport {
  runId: string;
  startedAt: string;
  durationMs: number;
  candidate: string;
  judge: string;
  dataset: string;
  datasetHash: string | null;
  settings: RunSettings | null;
  caseCount: number;
  dimensions: Partial<Record<Dimension, DimensionStats>>;
  schemaConformity: Record<string, { cases: number; conform: number; rate: number }> | null;
  latency: LatencyStats | null;
  inputTokens: number;
  outputTokens: number;
  totalCost: number | null;
  costPerCorrect: number | null;
  currency: string;
  warnings: string[];
  cases: CaseResult[];
  reportFile: string | null;
}

export interface RunRequest {
  datasetId?: string;
  provider?: string;
  model?: string;
  caseIds?: string[];
  useJudge?: boolean;
  judgeProvider?: string;
  judgeModel?: string;
  temperature?: number;
  maxTokens?: number;
  allowFallback?: boolean;
  systemPrompt?: string;
}

export type JobStatus = 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED';

export interface JobView {
  id: string;
  status: JobStatus;
  cancelling: boolean;
  startedAt: string;
  finishedAt: string | null;
  datasetId: string;
  provider: string | null;
  model: string | null;
  judgeProvider: string | null;
  judgeModel: string | null;
  total: number;
  done: number;
  lastCase: string | null;
  reportId: string | null;
  error: string | null;
}

export type RegressionStatus = 'REGRESSION' | 'IMPROVEMENT' | 'STABLE' | 'NOT_COMPARABLE';

export interface ReportRef {
  id: string;
  runId: string;
  startedAt: string;
  candidate: string;
  judge: string;
  dataset: string;
  datasetHash: string | null;
  caseCount: number;
}

export interface DimensionDelta {
  baselineRate: number | null;
  candidateRate: number | null;
  /** candidat - reference, en points de pourcentage. */
  deltaPoints: number | null;
  compared: number;
  regressed: number;
  improved: number;
  status: RegressionStatus;
}

export interface CostComparison {
  currency: string;
  baselineTotal: number | null;
  candidateTotal: number | null;
  baselinePerCase: number | null;
  candidatePerCase: number | null;
  baselinePerCorrect: number | null;
  candidatePerCorrect: number | null;
  deltaPerCorrectPercent: number | null;
}

export interface LatencyComparison {
  baselineP50: number | null;
  candidateP50: number | null;
  baselineAvg: number | null;
  candidateAvg: number | null;
  deltaP50Percent: number | null;
}

export interface Transition {
  dimension: Dimension;
  from: Verdict;
  to: Verdict;
  reason: string | null;
}

export interface CaseChange {
  id: string;
  category: string | null;
  kind: 'REGRESSION' | 'MIXED' | 'IMPROVEMENT';
  transitions: Transition[];
}

export interface RegressionReport {
  baseline: ReportRef;
  candidate: ReportRef;
  thresholdPoints: number;
  status: RegressionStatus;
  summary: string;
  coverage: {
    common: number;
    added: string[];
    removed: string[];
    modified: string[];
    fingerprintsMissing: boolean;
  };
  dimensions: Record<Dimension, DimensionDelta>;
  cost: CostComparison;
  latency: LatencyComparison;
  changes: CaseChange[];
  warnings: string[];
  file: string | null;
}

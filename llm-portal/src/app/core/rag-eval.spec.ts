import { AnswerResult, GenerationCase, RagEvalReport, checkFacts, generationMetrics, modelLabel, normalizeText, summarize } from './rag-eval';
import { RetrievalCase, RetrievalReport } from './rag.models';

function answer(overrides: Partial<AnswerResult> = {}): AnswerResult {
  return {
    answer: '',
    factsFound: [],
    factsMissing: [],
    inputTokens: 100,
    outputTokens: 20,
    cost: 0,
    currency: 'USD',
    latencyMs: 1000,
    judge: null,
    error: null,
    ...overrides,
  };
}

function retrievalCase(id: string, status: RetrievalCase['status'], rank: number | null = 1): RetrievalCase {
  return {
    id,
    category: 'chiffre',
    question: `question ${id}`,
    document: status === 'OK' ? 'doc.pdf' : null,
    pages: [3],
    facts: ['45 jours'],
    abstain: status === 'OUT_OF_CORPUS',
    status,
    rank,
    variantsCovered: null,
    documentPrecision: 1,
    hits: [],
  };
}

describe('checkFacts', () => {
  it('retrouve un fait malgre la casse, les accents et les espaces insecables', () => {
    expect(checkFacts('Conservées 45 Jours après publication', ['45 jours'])).toEqual({ found: ['45 jours'], missing: [] });
    expect(normalizeText('Réinitialisation  Zigbee')).toBe('reinitialisation zigbee');
  });

  it("accepte l'une des formes d'un fait a|b, et liste les faits manquants", () => {
    expect(checkFacts('Comptez quatre heures et demie.', ['4,5|4.5|quatre heures et demie', '4 A'])).toEqual({
      found: ['4,5|4.5|quatre heures et demie'],
      missing: ['4 A'],
    });
  });
});

describe('generationMetrics', () => {
  it('separe exactitude, fidelite et abstention, et ne compte que les questions concernees', () => {
    const retrieval = [retrievalCase('a', 'OK'), retrievalCase('b', 'OK'), retrievalCase('h', 'OUT_OF_CORPUS', null)];
    const cases: GenerationCase[] = [
      { id: 'a', rag: answer({ judge: { fidele: true, abstention: false, justification: '' } }), llm: answer({ factsMissing: ['45 jours'] }) },
      { id: 'b', rag: answer({ factsMissing: ['45 jours'], judge: { fidele: false, abstention: false, justification: '' } }), llm: answer() },
      { id: 'h', rag: answer({ judge: { fidele: true, abstention: true, justification: '' } }), llm: answer() },
    ];

    const m = generationMetrics(retrieval, cases);

    expect(m.answerable).toBe(2);
    expect(m.ragAccuracy).toBe(0.5);
    expect(m.llmAccuracy).toBe(0.5);
    expect(m.ragFaithfulness).toBeCloseTo(2 / 3);
    expect(m.ragAbstention).toBe(1);
    expect(m.ragFalseAbstention).toBe(0);
  });

  it("une reponse en erreur compte comme fausse, et un cout inconnu rend le total inconnu", () => {
    const retrieval = [retrievalCase('a', 'OK')];
    const cases: GenerationCase[] = [{ id: 'a', rag: answer({ error: 'Gateway injoignable', cost: null }), llm: answer() }];

    const m = generationMetrics(retrieval, cases);

    expect(m.ragAccuracy).toBe(0);
    expect(m.errors).toBe(1);
    expect(m.ragCost).toBeNull();
    expect(m.llmCost).toBe(0);
  });
});

describe('summarize', () => {
  const retrieval = {
    dataset: 'test',
    runAt: '2026-10-23T09:00:00Z',
    topK: 5,
    scope: 'ALL',
    index: { embeddingModel: 'bge-m3', chunker: 'token(400/80)', documents: 14, chunks: 1095, sectionedChunks: 140, sectionInEmbedding: false },
    metrics: {
      evaluated: 32, skipped: 0, outOfCorpus: 3, recallAt1: 0.875, recallAtK: 1, mrr: 0.92,
      variantCoverage: 1, documentPrecision: 0.8, meanTopScoreAnswerable: 0.71, meanTopScoreOutOfCorpus: 0.51,
    },
    cases: [],
  } as RetrievalReport;

  it('dit en clair combien de questions trouvent la bonne page, et sur combien', () => {
    const lines = summarize({ label: 'x', runAt: '', topK: 5, scope: 'ALL', provider: null, model: null, retrieval, generation: null });

    expect(lines[0]).toContain('28 questions sur 32 (87,5 %)');
    expect(lines[0]).toContain('32 sur 32 (100 %)');
    // 0,71 contre 0,51 : trop proche pour qu'un seuil suffise.
    expect(lines.join(' ')).toContain('seuil de score');
  });

  it("compare a un rapport precedent quand on lui en donne un", () => {
    const report: RagEvalReport = { label: 'x', runAt: '', topK: 5, scope: 'ALL', provider: null, model: null, retrieval, generation: null };
    const baseline = { id: 'b', runAt: '', label: 'Avant sections', topK: 5, retrieval: { ...retrieval.metrics, recallAtK: 0.969 }, index: retrieval.index, generation: null };

    expect(summarize(report, baseline).at(-1)).toContain('« Avant sections » : bonne page dans les 5 extraits 96,9 % → 100 %');
  });
});

describe('modelLabel', () => {
  it('dit quel modele a repondu ou juge, et le defaut du Gateway quand rien n est impose', () => {
    expect(modelLabel({ provider: 'deepseek', model: 'deepseek-flash' })).toBe('deepseek · deepseek-flash');
    expect(modelLabel({ provider: 'openai', model: null })).toBe('openai');
    expect(modelLabel(null)).toBe('défaut du Gateway');
  });
});

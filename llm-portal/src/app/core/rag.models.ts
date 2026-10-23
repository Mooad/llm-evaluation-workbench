/**
 * Contrat de llm-rag (ingestion et recherche), tel qu'il passe sur le fil.
 * Source de verite : les records Java de llm-rag (api/dto, document, search) — garder les deux alignes.
 */

export type DocumentStatus = 'PROCESSING' | 'READY' | 'FAILED';

/** Etat d'un document dans un index (une strategie de decoupage). */
export interface IndexState {
  status: DocumentStatus;
  /** Sections extraites avec du texte (pages non vides pour un PDF). null tant que PROCESSING. */
  sections: number | null;
  chunks: number | null;
  error: string | null;
  completedAt: string | null;
}

export interface RagDocument {
  documentId: string;
  fileName: string;
  /** Format reconnu ("pdf"). null pour les documents indexes avant son introduction. */
  sourceType: string | null;
  /** Groupe choisi a l'import, pour restreindre la recherche ; null = aucun. */
  collection: string | null;
  sha256: string;
  /** Etat par nom d'index ("A", "B"...). */
  indexes: Record<string, IndexState>;
  /** Faux pour un document importe avant la conservation des fichiers : il ne peut pas etre reconstruit. */
  fileStored: boolean;
  /** Statut d'ensemble : en cours si un index l'est, en echec si un index l'est. */
  status: DocumentStatus;
  submittedAt: string;
}

/** Un index : une strategie de decoupage, dans sa collection Qdrant (GET /indexes). */
export interface SearchIndexView {
  name: string;
  label: string;
  /** Reglages effectifs : "token(400/80)", "section(<=800)". */
  chunker: string;
  collection: string;
  isDefault: boolean;
  /** documents = tous les documents connus ; ready = ceux deja indexes ici. */
  documents: number;
  ready: number;
  processing: number;
  failed: number;
  chunks: number;
}

/** Reponse de POST /indexes/{name}/rebuild. */
export interface RebuildResult {
  index: string;
  scheduled: number;
  /** Documents sans fichier conserve : a reimporter. */
  skipped: string[];
  busy: string[];
}

/** 202 = ingestion lancee ; 200 + alreadyKnown = meme contenu deja indexe ou en cours. */
export interface SubmitResponse {
  documentId: string;
  status: DocumentStatus;
  alreadyKnown: boolean;
}

/** Ou citer un passage : une page de PDF, ou un intervalle d'une video (a venir). */
export type SourceLocation =
  | { type: 'page'; page: number }
  | { type: 'pageRange'; from: number; to: number }
  | { type: 'timeRange'; startMs: number; endMs: number };

export interface SearchHit {
  documentId: string;
  fileName: string;
  sourceType: string | null;
  chunkIndex: number;
  location: SourceLocation;
  /** Titre de la section du document (sommaire PDF) ; null si le document n'a pas de structure. */
  section: string | null;
  /** Forme lisible prete a afficher : "notice.pdf, page 365". */
  citation: string;
  /** Similarite cosine calculee par Qdrant : un classement, pas une probabilite. */
  score: number;
  excerpt: string;
  /** Chunk complet : c'est lui qui part dans le prompt. */
  content: string;
}

export interface SearchRequest {
  query: string;
  /** Vide = tous les documents indexes. */
  documentIds: string[];
  topK: number;
  /** Groupes de documents ; absent ou vide = tous. */
  collections?: string[];
  /** Titres de section exacts ; absent ou vide = toutes. */
  sections?: string[];
  /** Index (strategie de decoupage) ou chercher ; absent = celui par defaut. */
  index?: string;
}

export interface SearchResponse {
  query: string;
  topK: number;
  results: SearchHit[];
}

export interface RagError {
  error: string;
  message: string;
}

// --- Evaluation (POST /evaluations/retrieval, /evaluations/reports) -----------------

export type RetrievalScope = 'ALL' | 'COLLECTION';

/** Taux de 0 a 1 ; null quand aucune question ne permet de les calculer. */
export interface RetrievalMetrics {
  evaluated: number;
  skipped: number;
  outOfCorpus: number;
  recallAt1: number | null;
  recallAtK: number | null;
  mrr: number | null;
  variantCoverage: number | null;
  documentPrecision: number | null;
  meanTopScoreAnswerable: number | null;
  meanTopScoreOutOfCorpus: number | null;
}

export type CaseStatus = 'OK' | 'OUT_OF_CORPUS' | 'DOCUMENT_ABSENT';

export interface RetrievalCase {
  id: string;
  category: string;
  question: string;
  document: string | null;
  pages: number[];
  facts: string[];
  abstain: boolean;
  status: CaseStatus;
  /** Position (1 = premier) du premier extrait sur une page attendue ; null = absent. */
  rank: number | null;
  variantsCovered: boolean | null;
  documentPrecision: number | null;
  hits: SearchHit[];
}

export interface RetrievalReport {
  dataset: string;
  runAt: string;
  topK: number;
  scope: RetrievalScope;
  index: {
    /** Index evalue ("A") et son nom lisible ; absents dans les rapports anterieurs aux index. */
    name?: string;
    label?: string;
    embeddingModel: string;
    chunker: string;
    documents: number;
    chunks: number;
    sectionedChunks: number | null;
    sectionInEmbedding: boolean;
  };
  metrics: RetrievalMetrics;
  cases: RetrievalCase[];
}

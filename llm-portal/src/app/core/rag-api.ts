import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';
import {
  RagDocument,
  RagError,
  RebuildResult,
  RetrievalReport,
  RetrievalScope,
  SearchIndexView,
  SearchRequest,
  SearchResponse,
  SubmitResponse,
} from './rag.models';

/** Prefixe relaye vers llm-rag par le serveur de dev (proxy.conf.mjs). Pas "/rag" : c'est une route du portail. */
const RAG = '/rag-api';

/**
 * Client de llm-rag. Le portail parle a deux services : llm-rag retrouve les extraits,
 * le Gateway genere la reponse. Aucun des deux ne connait l'autre.
 */
@Injectable({ providedIn: 'root' })
export class RagApi {
  private readonly http = inject(HttpClient);

  documents(): Observable<RagDocument[]> {
    return this.http.get<RagDocument[]>(`${RAG}/documents`).pipe(catchError(toRagError));
  }

  document(documentId: string): Observable<RagDocument> {
    return this.http
      .get<RagDocument>(`${RAG}/documents/${encodeURIComponent(documentId)}`)
      .pipe(catchError(toRagError));
  }

  /** @param collection groupe du document ; vide = garder celui qu'il a deja */
  upload(file: File, collection = ''): Observable<SubmitResponse> {
    const form = new FormData();
    form.append('file', file, file.name);
    if (collection.trim()) {
      form.append('collection', collection.trim());
    }
    return this.http.post<SubmitResponse>(`${RAG}/documents`, form).pipe(catchError(toRagError));
  }

  /** Retire le document de tous les index, avec son fichier. 409 s'il est en cours d'indexation. */
  deleteDocument(documentId: string): Observable<void> {
    return this.http.delete<void>(`${RAG}/documents/${encodeURIComponent(documentId)}`).pipe(catchError(toRagError));
  }

  indexes(): Observable<SearchIndexView[]> {
    return this.http.get<SearchIndexView[]>(`${RAG}/indexes`).pipe(catchError(toRagError));
  }

  /** Reindexe tous les documents dans cet index, a partir des fichiers conserves par llm-rag. */
  rebuild(index: string): Observable<RebuildResult> {
    return this.http
      .post<RebuildResult>(`${RAG}/indexes/${encodeURIComponent(index)}/rebuild`, null)
      .pipe(catchError(toRagError));
  }

  search(request: SearchRequest): Observable<SearchResponse> {
    return this.http.post<SearchResponse>(`${RAG}/search`, request).pipe(catchError(toRagError));
  }

  /** Mesure de la recherche seule sur le jeu de questions de llm-rag (environ 1 s par question). */
  evaluateRetrieval(topK: number, scope: RetrievalScope, index: string): Observable<RetrievalReport> {
    const params: Record<string, string | number> = { topK, scope };
    if (index) {
      params['index'] = index;
    }
    return this.http
      .post<RetrievalReport>(`${RAG}/evaluations/retrieval`, null, { params })
      .pipe(catchError(toRagError));
  }

  /** Les rapports sont stockes par llm-rag : l'historique ne depend pas du navigateur. */
  saveReport<T extends object>(report: T): Observable<T & { id: string }> {
    return this.http.post<T & { id: string }>(`${RAG}/evaluations/reports`, report).pipe(catchError(toRagError));
  }

  reports<T>(): Observable<T[]> {
    return this.http.get<T[]>(`${RAG}/evaluations/reports`).pipe(catchError(toRagError));
  }

  report<T>(id: string): Observable<T> {
    return this.http.get<T>(`${RAG}/evaluations/reports/${encodeURIComponent(id)}`).pipe(catchError(toRagError));
  }
}

/** Toujours une RagError a la sortie : le composant n'a pas a connaitre HttpErrorResponse. */
export function toRagError(error: HttpErrorResponse): Observable<never> {
  const body = error.error;
  const ragError: RagError =
    body && typeof body === 'object' && 'error' in body
      ? { error: String(body.error), message: String(body.message ?? '') }
      : // Sans corps JSON, un 5xx vient du proxy de dev : llm-rag ne repond pas.
        error.status === 0 || error.status >= 500
        ? { error: 'rag_unreachable', message: 'llm-rag injoignable (port 8082 par défaut)' }
        : { error: `http_${error.status}`, message: error.statusText };
  return throwError(() => ragError);
}

import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  DatasetSummary,
  EvalCase,
  EvalReport,
  JobView,
  RegressionReport,
  ReportSummary,
  RunRequest,
} from './eval.models';

const API = '/api/eval';

/** Evaluations : datasets, executions en arriere-plan, rapports et regressions. */
@Injectable({ providedIn: 'root' })
export class EvalApi {
  private readonly http = inject(HttpClient);

  datasets(): Observable<DatasetSummary[]> {
    return this.http.get<DatasetSummary[]>(`${API}/datasets`);
  }

  datasetCases(id: string): Observable<EvalCase[]> {
    return this.http.get<EvalCase[]>(`${API}/datasets/${encodeURIComponent(id)}/cases`);
  }

  /** Le corps est le fichier tel quel : le Gateway le valide avant de l'ecrire. */
  importDataset(id: string, json: string): Observable<DatasetSummary> {
    return this.http.post<DatasetSummary>(`${API}/datasets/${encodeURIComponent(id)}`, json, {
      headers: { 'Content-Type': 'application/json' },
    });
  }

  startRun(request: RunRequest): Observable<JobView> {
    return this.http.post<JobView>(`${API}/runs`, request);
  }

  runs(): Observable<JobView[]> {
    return this.http.get<JobView[]>(`${API}/runs`);
  }

  run(id: string): Observable<JobView> {
    return this.http.get<JobView>(`${API}/runs/${encodeURIComponent(id)}`);
  }

  cancelRun(id: string): Observable<JobView> {
    return this.http.post<JobView>(`${API}/runs/${encodeURIComponent(id)}/cancel`, {});
  }

  reports(): Observable<ReportSummary[]> {
    return this.http.get<ReportSummary[]>(`${API}/reports`);
  }

  report(id: string): Observable<EvalReport> {
    return this.http.get<EvalReport>(`${API}/reports/${encodeURIComponent(id)}`);
  }

  deleteReport(id: string): Observable<void> {
    return this.http.delete<void>(`${API}/reports/${encodeURIComponent(id)}`);
  }

  /** Calcule la regression et ecrit son rapport JSON cote Gateway. */
  compare(baseline: string, candidate: string, thresholdPoints?: number): Observable<RegressionReport> {
    return this.http.post<RegressionReport>(`${API}/regressions`, { baseline, candidate, thresholdPoints });
  }
}

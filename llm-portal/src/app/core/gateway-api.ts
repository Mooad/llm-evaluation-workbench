import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, of, throwError } from 'rxjs';
import {
  ChatRequest,
  ChatResponse,
  GatewayError,
  GenerationDefaults,
  PricingView,
  ProviderSettings,
  ProvidersInfo,
  ProvidersStatus,
  SettingsView,
  StreamEvent,
  StructuredChatRequest,
  StructuredChatResponse,
} from './gateway.models';
import { SseParser } from './sse-parser';

/** Prefixe relaye vers le Gateway par le serveur de dev (proxy.conf.mjs). */
const API = '/api';

@Injectable({ providedIn: 'root' })
export class GatewayApi {
  private readonly http = inject(HttpClient);

  providers(): Observable<ProvidersInfo> {
    return this.http.get<ProvidersInfo>(`${API}/providers`);
  }

  /** Le Gateway repond 503 quand aucun fournisseur n'est UP, mais le corps reste exploitable. */
  status(): Observable<ProvidersStatus> {
    return this.http.get<ProvidersStatus>(`${API}/providers/status`).pipe(
      catchError((error: HttpErrorResponse) =>
        error.status === 503 && error.error?.providers ? of(error.error as ProvidersStatus) : throwError(() => error),
      ),
    );
  }

  settings(): Observable<SettingsView> {
    return this.http.get<SettingsView>(`${API}/settings`);
  }

  /** Reecrit le fichier de defauts du Gateway : s'applique a tous ses clients. */
  updateSettings(provider: string, defaults: GenerationDefaults): Observable<ProviderSettings> {
    return this.http.put<ProviderSettings>(`${API}/settings/${encodeURIComponent(provider)}`, defaults);
  }

  pricing(): Observable<PricingView> {
    return this.http.get<PricingView>(`${API}/pricing`);
  }

  /** Generation sans streaming : la reponse arrive d'un bloc, avec tokens, latence et cout. */
  chat(request: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>(`${API}/chat`, request);
  }

  /** Sortie JSON validee par le Gateway : 422 si le modele ne respecte pas le schema. */
  structured<T>(request: StructuredChatRequest): Observable<StructuredChatResponse<T>> {
    return this.http.post<StructuredChatResponse<T>>(`${API}/chat/structured`, request);
  }

  /**
   * POST /chat/stream lu avec fetch : chaque fragment est emis des son arrivee.
   * Se desabonner coupe la connexion — c'est le bouton "Arreter".
   */
  stream(request: ChatRequest): Observable<StreamEvent> {
    return new Observable<StreamEvent>((subscriber) => {
      const controller = new AbortController();

      (async () => {
        const response = await fetch(`${API}/chat/stream`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
          body: JSON.stringify(request),
          signal: controller.signal,
        });

        if (!response.ok || !response.body) {
          // Avant l'ouverture du flux, le Gateway repond en JSON classique (400, 502...).
          subscriber.next({ type: 'error', error: await readError(response) });
          subscriber.complete();
          return;
        }

        const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
        const parser = new SseParser();
        for (;;) {
          const { value, done } = await reader.read();
          if (done) {
            break;
          }
          for (const message of parser.push(value)) {
            subscriber.next(toEvent(message.event, message.data));
          }
        }
        subscriber.complete();
      })().catch((error: unknown) => {
        if (!controller.signal.aborted) {
          subscriber.error(error);
        }
      });

      return () => controller.abort();
    });
  }
}

function toEvent(event: string, data: string): StreamEvent {
  const payload = JSON.parse(data);
  switch (event) {
    case 'delta':
      return { type: 'delta', content: payload.content };
    case 'done':
      return { type: 'done', summary: payload };
    default:
      return { type: 'error', error: payload as GatewayError };
  }
}

async function readError(response: Response): Promise<GatewayError> {
  try {
    const body = await response.json();
    return { error: body.error ?? `http_${response.status}`, message: body.message ?? response.statusText };
  } catch {
    return { error: `http_${response.status}`, message: response.statusText || 'Gateway injoignable' };
  }
}

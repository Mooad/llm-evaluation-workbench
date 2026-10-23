import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { GatewayApi } from '../core/gateway-api';
import { ChatRequest, StreamEvent, StreamSummary } from '../core/gateway.models';
import { RagApi } from '../core/rag-api';
import { RagLibrary } from '../core/rag-library';
import { RagDocument, SearchRequest, SearchResponse } from '../core/rag.models';
import { RagSettings } from '../core/rag-settings';
import { RagConversation } from './rag-conversation';

const READY: RagDocument = {
  documentId: 'doc-1',
  fileName: 'notice.pdf',
  sourceType: 'pdf',
  collection: 'notices',
  sha256: 'sha',
  indexes: {
    A: { status: 'READY', sections: 469, chunks: 780, error: null, completedAt: '2026-10-16T10:01:00Z' },
    B: { status: 'READY', sections: 469, chunks: 612, error: null, completedAt: '2026-10-16T10:02:00Z' },
  },
  fileStored: true,
  status: 'READY',
  submittedAt: '2026-10-16T10:00:00Z',
};

const RESPONSE: SearchResponse = {
  query: 'Quelle pression ?',
  topK: 5,
  results: [
    {
      documentId: 'doc-1',
      fileName: 'notice.pdf',
      sourceType: 'pdf',
      chunkIndex: 618,
      location: { type: 'page', page: 365 },
      section: 'Pressions de gonflage',
      citation: 'notice.pdf, page 365',
      score: 0.71,
      excerpt: 'Pression de gonflage…',
      content: 'Pression de gonflage : voir étiquette porte conducteur.',
    },
  ],
};

describe('RagConversation', () => {
  let searches: SearchRequest[];
  let requests: ChatRequest[];
  let stream: Subject<StreamEvent>;
  let searchResult: () => Observable<SearchResponse>;
  const activeDocuments = signal<RagDocument[]>([READY]);

  beforeEach(() => {
    sessionStorage.clear();
    searches = [];
    requests = [];
    stream = new Subject<StreamEvent>();
    searchResult = () => of(RESPONSE);
    activeDocuments.set([READY]);

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        {
          provide: RagApi,
          useValue: {
            search: (request: SearchRequest) => {
              searches.push(request);
              return searchResult();
            },
          },
        },
        {
          provide: GatewayApi,
          useValue: {
            stream: (request: ChatRequest) => {
              requests.push(request);
              return stream;
            },
          },
        },
        { provide: RagLibrary, useValue: { activeDocuments, selectedIndex: signal({ name: 'B' }) } },
      ],
    });
  });

  it("cherche dans les documents actifs, puis envoie les extraits au Gateway", () => {
    const conversation = TestBed.inject(RagConversation);

    conversation.send('Quelle pression ?');
    stream.next({ type: 'delta', content: 'Voir [1].' });
    stream.next({ type: 'done', summary: { inputTokens: 900, estimatedCost: 0 } as StreamSummary });

    expect(searches).toEqual([{ query: 'Quelle pression ?', documentIds: ['doc-1'], topK: 5, collections: [], index: 'B' }]);
    expect(requests).toHaveLength(1);
    expect(requests[0].messages.at(-1)!.content).toContain('[1] notice.pdf, p. 365');
    const assistant = conversation.turns()[1];
    expect(assistant).toMatchObject({ mode: 'rag', status: 'done', content: 'Voir [1].' });
    expect(assistant.hits).toHaveLength(1);
    expect(assistant.searchMs).toBeGreaterThanOrEqual(0);
    expect(conversation.lastHits()).toEqual(RESPONSE.results);
  });

  it('avec une collection active, demande a llm-rag de filtrer sur cette collection', () => {
    TestBed.inject(RagSettings).collection.set('notices');
    const conversation = TestBed.inject(RagConversation);

    conversation.send('Quelle pression ?');

    expect(searches[0].collections).toEqual(['notices']);
    expect(requests[0].messages.at(-1)!.content).toContain('p. 365 — Pressions de gonflage');
  });

  it('en mode LLM seul, envoie la question sans recherche ni extraits', () => {
    TestBed.inject(RagSettings).ragMode.set(false);
    const conversation = TestBed.inject(RagConversation);

    conversation.send('Quelle pression ?');

    expect(searches).toHaveLength(0);
    expect(requests[0].messages).toEqual([{ role: 'user', content: 'Quelle pression ?' }]);
    expect(conversation.turns()[1].mode).toBe('llm');
  });

  it("une recherche en echec s'affiche sur le tour, sans appeler le Gateway", () => {
    searchResult = () => throwError(() => ({ error: 'rag_unreachable', message: 'llm-rag injoignable' }));
    const conversation = TestBed.inject(RagConversation);

    conversation.send('Quelle pression ?');

    expect(requests).toHaveLength(0);
    expect(conversation.turns()[1]).toMatchObject({
      status: 'error',
      error: { error: 'rag_unreachable', message: 'llm-rag injoignable' },
    });
  });

  it('refuse d envoyer en mode RAG sans document actif', () => {
    activeDocuments.set([]);
    const conversation = TestBed.inject(RagConversation);

    expect(conversation.blocker()).not.toBeNull();
    expect(conversation.send('Quelle pression ?')).toBe(false);
    expect(conversation.turns()).toHaveLength(0);
  });
});

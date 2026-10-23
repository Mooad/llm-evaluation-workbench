import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { RagApi } from './rag-api';
import { IndexState, RagDocument, RagError, SearchIndexView } from './rag.models';
import { RagSettings } from './rag-settings';

/** llm-rag accepte 50 Mo (spring.servlet.multipart) : on refuse avant d'envoyer 50 Mo pour rien. */
export const MAX_UPLOAD_BYTES = 50 * 1024 * 1024;
const POLL_MS = 2000;

export type Notice = { kind: 'info' | 'error'; text: string };

/**
 * Les documents et les index de llm-rag, vus du portail : liste, import, suppression,
 * reconstruction d'un index, suivi de l'indexation.
 *
 * Service racine : la liste et le suivi survivent a un changement d'onglet du portail.
 * Tant qu'un document est en cours dans un index, la liste est relue toutes les 2 s (llm-rag
 * n'envoie pas d'evenement de fin d'indexation).
 */
@Injectable({ providedIn: 'root' })
export class RagLibrary {
  private readonly api = inject(RagApi);
  private readonly settings = inject(RagSettings);

  readonly documents = signal<RagDocument[]>([]);
  readonly indexes = signal<SearchIndexView[]>([]);
  readonly loaded = signal(false);
  readonly error = signal<RagError | null>(null);
  readonly uploading = signal(false);
  /** Message du dernier import, de la derniere suppression ou reconstruction ; null = rien a dire. */
  readonly notice = signal<Notice | null>(null);

  /** Collection donnee aux prochains imports ; '' = aucune (ou garder celle du document). */
  readonly uploadCollection = signal('');

  /** L'index interroge : celui choisi, sinon celui par defaut de llm-rag. */
  readonly selectedIndex = computed(() => {
    const chosen = this.settings.index();
    const known = this.indexes();
    return known.find((i) => i.name === chosen) ?? known.find((i) => i.isDefault) ?? known[0] ?? null;
  });

  /** Les collections existantes, pour les proposer a l'import et au filtre. */
  readonly collections = computed(() =>
    [...new Set(this.documents().map((d) => d.collection).filter((c): c is string => !!c))].sort(),
  );

  /** Documents de la collection active (tous si aucune). */
  readonly visibleDocuments = computed(() => {
    const collection = this.settings.collection();
    return collection ? this.documents().filter((d) => d.collection === collection) : this.documents();
  });

  /** Documents interroges : de la collection active, prets dans l'index choisi, et non decoches. */
  readonly activeDocuments = computed(() => {
    const index = this.selectedIndex()?.name;
    return this.visibleDocuments().filter(
      (d) => !!index && d.indexes[index]?.status === 'READY' && this.settings.isIncluded(d.documentId),
    );
  });

  readonly activeChunks = computed(() => {
    const index = this.selectedIndex()?.name;
    return this.activeDocuments().reduce((sum, d) => sum + ((index && d.indexes[index]?.chunks) || 0), 0);
  });

  readonly processing = computed(() => this.documents().some((d) => d.status === 'PROCESSING'));

  private pollTimer?: ReturnType<typeof setTimeout>;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.pollTimer));
    this.refresh();
  }

  refresh(): void {
    clearTimeout(this.pollTimer);
    this.api.indexes().subscribe({ next: (indexes) => this.indexes.set(indexes), error: () => undefined });
    this.api.documents().subscribe({
      next: (documents) => {
        this.documents.set(documents);
        this.error.set(null);
        this.loaded.set(true);
        if (this.processing()) {
          this.pollTimer = setTimeout(() => this.refresh(), POLL_MS);
        }
      },
      error: (error: RagError) => {
        this.error.set(error);
        this.loaded.set(true);
      },
    });
  }

  /** Etat du document dans l'index choisi ; undefined = jamais indexe dans cet index. */
  stateIn(document: RagDocument, index = this.selectedIndex()?.name): IndexState | undefined {
    return index ? document.indexes[index] : undefined;
  }

  upload(file: File): void {
    const refusal = checkFile(file);
    if (refusal) {
      this.notice.set({ kind: 'error', text: refusal });
      return;
    }
    this.uploading.set(true);
    this.notice.set(null);
    this.api.upload(file, this.uploadCollection()).subscribe({
      next: (response) => {
        this.uploading.set(false);
        if (response.alreadyKnown) {
          this.notice.set({
            kind: 'info',
            text: `Contenu déjà indexé dans tous les index (${response.documentId}) : rien n'a été recalculé.`,
          });
        }
        // Un document reimporte puis decoche doit redevenir actif : c'est ce que l'import demande.
        if (!this.settings.isIncluded(response.documentId)) {
          this.settings.toggleDocument(response.documentId);
        }
        this.refresh();
      },
      error: (error: RagError) => {
        this.uploading.set(false);
        this.notice.set({ kind: 'error', text: error.message || error.error });
      },
    });
  }

  /** Irreversible : retire le document de tous les index et supprime son fichier dans llm-rag. */
  delete(document: RagDocument): void {
    this.api.deleteDocument(document.documentId).subscribe({
      next: () => {
        this.notice.set({ kind: 'info', text: `${document.fileName} supprimé de tous les index.` });
        this.refresh();
      },
      error: (error: RagError) => this.notice.set({ kind: 'error', text: error.message || error.error }),
    });
  }

  /** Reindexe tous les documents dans cet index ; la progression se lit dans la liste. */
  rebuild(index: string): void {
    this.api.rebuild(index).subscribe({
      next: (result) => {
        const skipped = result.skipped.length
          ? ` ${result.skipped.length} sans fichier conservé, à réimporter : ${result.skipped.join(', ')}.`
          : '';
        const busy = result.busy.length ? ` ${result.busy.length} déjà en cours.` : '';
        this.notice.set({
          kind: result.skipped.length ? 'error' : 'info',
          text: `Index ${result.index} : ${result.scheduled} document(s) en cours de réindexation.${skipped}${busy}`,
        });
        this.refresh();
      },
      error: (error: RagError) => this.notice.set({ kind: 'error', text: error.message || error.error }),
    });
  }
}

/** Refus cote navigateur ; le contenu reel (signature %PDF-) reste verifie par llm-rag. */
export function checkFile(file: File): string | null {
  if (file.size === 0) {
    return 'Fichier vide.';
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return `Fichier trop lourd (${Math.round(file.size / 1024 / 1024)} Mo, maximum 50 Mo).`;
  }
  return null;
}

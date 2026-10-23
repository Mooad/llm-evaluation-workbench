import { Component, inject, signal } from '@angular/core';
import { formatCount } from '../core/format';
import { RagLibrary } from '../core/rag-library';
import { DocumentStatus, RagDocument } from '../core/rag.models';
import { RagSettings } from '../core/rag-settings';

interface SourceKind {
  label: string;
  /** false = pas encore de reader dans llm-rag : affiche, mais desactive. */
  available: boolean;
}

/** Les formats que llm-rag sait (ou saura) ingerer. Seul le PDF a un reader aujourd'hui. */
const KINDS: SourceKind[] = [
  { label: 'PDF', available: true },
  { label: 'HTML', available: false },
  { label: 'URL', available: false },
  { label: 'Vidéo', available: false },
  { label: 'Image', available: false },
];

const STATUS_LABELS: Record<DocumentStatus, string> = {
  PROCESSING: 'Indexation…',
  READY: 'Prêt',
  FAILED: 'Échec',
};

@Component({
  selector: 'app-rag-library-panel',
  templateUrl: './rag-library-panel.html',
  styleUrl: './rag-library-panel.scss',
})
export class RagLibraryPanel {
  protected readonly library = inject(RagLibrary);
  protected readonly settings = inject(RagSettings);

  protected readonly kinds = KINDS;
  protected readonly statusLabels = STATUS_LABELS;
  protected readonly formatCount = formatCount;
  protected readonly dragging = signal(false);

  protected onDragOver(event: DragEvent): void {
    // Sans preventDefault, le navigateur ouvrirait le fichier au lieu de le deposer ici.
    event.preventDefault();
    this.dragging.set(true);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) {
      this.library.upload(file);
    }
  }

  /** Suppression irreversible (chunks de tous les index et fichier) : confirmee avant l'appel. */
  protected confirmDelete(document: RagDocument): void {
    if (window.confirm(`Supprimer « ${document.fileName} » de tous les index ? Cette action est définitive.`)) {
      this.library.delete(document);
    }
  }

  protected onPick(input: HTMLInputElement): void {
    const file = input.files?.[0];
    if (file) {
      this.library.upload(file);
    }
    // Remis a zero : choisir deux fois le meme fichier doit redeclencher l'import.
    input.value = '';
  }
}

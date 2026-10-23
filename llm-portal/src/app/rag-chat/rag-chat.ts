import { Component, ElementRef, afterRenderEffect, inject, signal, viewChild } from '@angular/core';
import { formatCost, formatCount, formatMs } from '../core/format';
import { GatewayConfig } from '../core/gateway-config';
import { RagLibrary } from '../core/rag-library';
import { locationLabel } from '../core/rag-prompt';
import { MAX_TOP_K, RagSettings } from '../core/rag-settings';
import { SessionSettings } from '../core/session-settings';
import { RagConversation } from './rag-conversation';
import { RagLibraryPanel } from './rag-library-panel';
import { RagSources } from './rag-sources';

const SUGGESTIONS = [
  'Quelles sont les informations clés de ce document ?',
  'Quelles recommandations ce document donne-t-il ?',
  'Explique simplement un concept important du document.',
];

@Component({
  selector: 'app-rag-chat',
  imports: [RagLibraryPanel, RagSources],
  templateUrl: './rag-chat.html',
  styleUrl: './rag-chat.scss',
})
export class RagChat {
  private readonly transcript = viewChild<ElementRef<HTMLElement>>('transcript');
  private readonly composer = viewChild<ElementRef<HTMLTextAreaElement>>('composer');

  protected readonly conversation = inject(RagConversation);
  protected readonly library = inject(RagLibrary);
  protected readonly settings = inject(RagSettings);
  protected readonly session = inject(SessionSettings);
  protected readonly config = inject(GatewayConfig);

  protected readonly suggestions = SUGGESTIONS;
  protected readonly maxTopK = MAX_TOP_K;
  protected readonly formatCost = formatCost;
  protected readonly formatCount = formatCount;
  protected readonly formatMs = formatMs;
  protected readonly locationLabel = locationLabel;

  protected readonly draft = signal('');

  private pinnedToBottom = true;

  constructor() {
    // Suit le texte qui arrive, sauf si l'utilisateur est remonte lire plus haut.
    afterRenderEffect(() => {
      this.conversation.turns();
      const el = this.transcript()?.nativeElement;
      if (el && this.pinnedToBottom) {
        el.scrollTop = el.scrollHeight;
      }
    });
  }

  /** Reindexer coute environ une minute d'embeddings par index pour le corpus de test : on confirme. */
  protected confirmRebuild(index: string): void {
    if (window.confirm(`Réindexer tous les documents dans l'index ${index} ? Les autres index ne changent pas.`)) {
      this.library.rebuild(index);
    }
  }

  /** La valeur bornee est reecrite dans le champ : 80 saisi doit s'afficher 20, la valeur reellement utilisee. */
  protected setTopK(input: HTMLInputElement): void {
    this.settings.setTopK(Number(input.value));
    input.value = String(this.settings.topK());
  }

  protected onScroll(el: HTMLElement): void {
    this.pinnedToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
  }

  protected onComposerKey(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      this.send();
    }
  }

  protected send(text = this.draft()): void {
    if (this.conversation.send(text)) {
      this.draft.set('');
      // Saisie puis Entree dans le meme cycle de rendu : le binding [value] passe de '' a ''
      // et ne touche pas au champ, qui garderait la question. On le vide donc aussi a la main.
      const composer = this.composer()?.nativeElement;
      if (composer) {
        composer.value = '';
      }
      this.pinnedToBottom = true;
    }
  }
}

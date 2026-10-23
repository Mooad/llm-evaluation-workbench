import { Component, input } from '@angular/core';
import { locationLabel } from '../core/rag-prompt';
import { SearchHit } from '../core/rag.models';

/** Les extraits retrouves pour une question, numerotes comme dans le prompt : [1], [2]... */
@Component({
  selector: 'app-rag-sources',
  templateUrl: './rag-sources.html',
  styleUrl: './rag-sources.scss',
})
export class RagSources {
  readonly hits = input.required<SearchHit[]>();

  protected readonly locationLabel = locationLabel;

  /** 0,71 : deux decimales suffisent pour classer, plus ferait croire a une precision qui n'existe pas. */
  protected score(value: number): string {
    return value.toFixed(2).replace('.', ',');
  }
}

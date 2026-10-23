import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { GatewayConfig } from '../core/gateway-config';
import { GenerationDefaults, ProviderSettings } from '../core/gateway.models';

type Choice = '' | 'on' | 'off';

/** Une ligne editable des defauts globaux : un fournisseur, ses trois reglages, son enregistrement. */
@Component({
  selector: 'app-defaults-row',
  templateUrl: './defaults-row.html',
  styleUrl: './defaults-row.scss',
})
export class DefaultsRow {
  private readonly config = inject(GatewayConfig);

  readonly settings = input.required<ProviderSettings>();

  // Brouillons reinitialises a chaque nouvelle valeur venue du Gateway (apres enregistrement).
  protected readonly reasoning = linkedSignal<Choice>(() => toChoice(this.settings().defaults.reasoning));
  protected readonly maxTokens = linkedSignal(() => toText(this.settings().defaults.maxTokens));
  protected readonly temperature = linkedSignal(() => toText(this.settings().defaults.temperature));

  protected readonly state = signal<'idle' | 'saving' | 'saved' | 'error'>('idle');
  protected readonly message = signal('');

  protected readonly dirty = computed(() => {
    const saved = this.settings().defaults;
    return (
      this.reasoning() !== toChoice(saved.reasoning) ||
      this.maxTokens() !== toText(saved.maxTokens) ||
      this.temperature() !== toText(saved.temperature)
    );
  });

  protected edit(field: 'reasoning' | 'maxTokens' | 'temperature', value: string): void {
    if (field === 'reasoning') {
      this.reasoning.set(value as Choice);
    } else {
      this[field].set(value);
    }
    this.state.set('idle');
  }

  protected save(): void {
    const defaults = this.parse();
    if (!defaults) {
      return;
    }
    this.state.set('saving');
    this.config.save(this.settings().provider, defaults).subscribe({
      next: () => this.state.set('saved'),
      error: (error: HttpErrorResponse) => {
        this.state.set('error');
        this.message.set(error.error?.message ?? 'Enregistrement impossible');
      },
    });
  }

  /** Validation locale : le Gateway revalide, mais l'erreur s'affiche ici sans aller-retour. */
  private parse(): GenerationDefaults | null {
    const maxTokens = toNumber(this.maxTokens());
    const temperature = toNumber(this.temperature());
    if (maxTokens !== null && (!Number.isInteger(maxTokens) || maxTokens < 1)) {
      return this.fail('Max tokens : entier positif, ou vide.');
    }
    if (temperature !== null && (temperature < 0 || temperature > 2)) {
      return this.fail('Température : entre 0 et 2, ou vide.');
    }
    const choice = this.reasoning();
    return { reasoning: choice === '' ? null : choice === 'on', maxTokens, temperature };
  }

  private fail(message: string): null {
    this.state.set('error');
    this.message.set(message);
    return null;
  }
}

function toChoice(value: boolean | null): Choice {
  return value === null ? '' : value ? 'on' : 'off';
}

function toText(value: number | null): string {
  return value === null ? '' : String(value);
}

function toNumber(value: string): number | null {
  const trimmed = value.trim().replace(',', '.');
  return trimmed === '' ? null : Number(trimmed);
}

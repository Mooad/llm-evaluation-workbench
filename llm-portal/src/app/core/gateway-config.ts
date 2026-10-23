import { Injectable, inject, signal } from '@angular/core';
import { Observable, forkJoin, tap } from 'rxjs';
import { GatewayApi } from './gateway-api';
import {
  GenerationDefaults,
  PricingView,
  ProviderSettings,
  ProvidersInfo,
  SettingsView,
} from './gateway.models';

/** Ce que le Gateway expose de sa configuration : fournisseurs, defauts globaux, tarifs. */
@Injectable({ providedIn: 'root' })
export class GatewayConfig {
  private readonly api = inject(GatewayApi);

  readonly providers = signal<ProvidersInfo | null>(null);
  readonly settings = signal<SettingsView | null>(null);
  readonly pricing = signal<PricingView | null>(null);
  readonly unreachable = signal(false);

  constructor() {
    this.refresh();
  }

  refresh(): void {
    forkJoin({
      providers: this.api.providers(),
      settings: this.api.settings(),
      pricing: this.api.pricing(),
    }).subscribe({
      next: ({ providers, settings, pricing }) => {
        this.providers.set(providers);
        this.settings.set(settings);
        this.pricing.set(pricing);
        this.unreachable.set(false);
      },
      error: () => this.unreachable.set(true),
    });
  }

  defaultsFor(provider: string): ProviderSettings | undefined {
    return this.settings()?.providers.find((p) => p.provider === provider);
  }

  save(provider: string, defaults: GenerationDefaults): Observable<ProviderSettings> {
    return this.api.updateSettings(provider, defaults).pipe(
      tap((updated) =>
        this.settings.update(
          (current) =>
            current && {
              ...current,
              providers: current.providers.map((p) => (p.provider === updated.provider ? updated : p)),
            },
        ),
      ),
    );
  }
}

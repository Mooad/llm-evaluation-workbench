import { Component, inject, signal } from '@angular/core';
import { GatewayApi } from '../core/gateway-api';
import { ProviderHealth } from '../core/gateway.models';
import { formatMs } from '../core/format';

/** Etat de chaque fournisseur, via les sondes legeres du Gateway (aucun token consomme). */
@Component({
  selector: 'app-provider-status',
  templateUrl: './provider-status.html',
  styleUrl: './provider-status.scss',
})
export class ProviderStatus {
  private readonly api = inject(GatewayApi);

  protected readonly providers = signal<ProviderHealth[]>([]);
  protected readonly defaultProvider = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly unreachable = signal(false);
  protected readonly formatMs = formatMs;

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.api.status().subscribe({
      next: (status) => {
        this.providers.set(status.providers);
        this.defaultProvider.set(status.default);
        this.unreachable.set(false);
        this.loading.set(false);
      },
      error: () => {
        this.providers.set([]);
        this.unreachable.set(true);
        this.loading.set(false);
      },
    });
  }
}

import { Component, computed, inject, signal } from '@angular/core';
import { GatewayConfig } from '../core/gateway-config';
import { formatCost, formatPrice } from '../core/format';
import { DefaultsRow } from './defaults-row';

const PER_MILLION = 1_000_000;

/** Reglages globaux du Gateway (modifiables) et tarifs appliques (lecture seule). */
@Component({
  selector: 'app-configuration',
  imports: [DefaultsRow],
  templateUrl: './configuration.html',
  styleUrl: './configuration.scss',
})
export class Configuration {
  protected readonly config = inject(GatewayConfig);
  protected readonly formatPrice = formatPrice;
  protected readonly formatCost = formatCost;

  // Volume simule : pour lire un tarif "par million" en cout concret.
  protected readonly inputTokens = signal('1000');
  protected readonly outputTokens = signal('500');

  protected readonly simulated = computed(() => {
    const pricing = this.config.pricing();
    const input = Math.max(0, Number(this.inputTokens()) || 0);
    const output = Math.max(0, Number(this.outputTokens()) || 0);
    return (pricing?.models ?? []).map((m) => ({
      ...m,
      inputPerToken: m.inputPer1m / PER_MILLION,
      outputPerToken: m.outputPer1m / PER_MILLION,
      cost: (input * m.inputPer1m + output * m.outputPer1m) / PER_MILLION,
    }));
  });
}

import { Origin } from './gateway.models';

/** Cout : null = inconnu (jamais affiche comme 0), 0 = local, sinon 3 chiffres significatifs. */
export function formatCost(amount: number | null | undefined, currency = 'USD'): string {
  if (amount === null || amount === undefined) {
    return 'inconnu';
  }
  if (amount === 0) {
    return `0 ${currency}`;
  }
  const value = new Intl.NumberFormat('fr-FR', { maximumSignificantDigits: 3 }).format(amount);
  return `${value} ${currency}`;
}

export function formatMs(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) {
    return '—';
  }
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1).replace('.', ',')} s`;
}

export function formatCount(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : new Intl.NumberFormat('fr-FR').format(value);
}

/** D'ou vient un reglage applique, vu du portail : REQUEST = envoye par la session. */
export function originLabel(origin: Origin): string {
  switch (origin) {
    case 'REQUEST':
      return 'session';
    case 'GLOBAL':
      return 'global';
    case 'PROVIDER':
      return 'fournisseur';
  }
}

export function formatReasoning(value: boolean | null | undefined): string {
  return value === null || value === undefined ? 'défaut' : value ? 'activé' : 'désactivé';
}

/** Prix unitaire : assez de chiffres pour lire un prix par token (ex. 0,0000003). */
export function formatPrice(amount: number, currency = 'USD'): string {
  if (amount === 0) {
    return `0 ${currency}`;
  }
  return `${new Intl.NumberFormat('fr-FR', { maximumSignificantDigits: 3, maximumFractionDigits: 10 }).format(amount)} ${currency}`;
}

/** Taux 0..1 en pourcentage lisible : 0.914 -> "91,4 %". */
export function formatRate(rate: number | null | undefined): string {
  return rate === null || rate === undefined
    ? '—'
    : `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 }).format(rate * 100)} %`;
}

/** Ecart en points de pourcentage, toujours signe : -8.6 -> "−8,6 pts". */
export function formatPoints(points: number | null | undefined): string {
  if (points === null || points === undefined) {
    return '—';
  }
  const value = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 }).format(Math.abs(points));
  return `${points > 0 ? '+' : points < 0 ? '−' : '±'}${value} pts`;
}

/** Ecart relatif signe : 12.5 -> "+12,5 %". */
export function formatPercentDelta(percent: number | null | undefined): string {
  if (percent === null || percent === undefined) {
    return '—';
  }
  const value = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 }).format(Math.abs(percent));
  return `${percent > 0 ? '+' : percent < 0 ? '−' : '±'}${value} %`;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) {
    return '—';
  }
  return new Intl.DateTimeFormat('fr-FR', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(iso));
}

/** Telecharge un objet en fichier JSON, sans aller-retour serveur. */
export function downloadJson(data: unknown, filename: string): void {
  const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
  const link = Object.assign(document.createElement('a'), { href: url, download: filename });
  link.click();
  URL.revokeObjectURL(url);
}

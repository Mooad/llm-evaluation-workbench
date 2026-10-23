import { formatCost, formatMs, formatPercentDelta, formatPoints, formatRate } from './format';

describe('formatCost', () => {
  it("n'affiche jamais un cout inconnu comme gratuit", () => {
    expect(formatCost(null)).toBe('inconnu');
    expect(formatCost(0)).toBe('0 USD');
  });

  it('garde trois chiffres significatifs sur les tres petits montants', () => {
    expect(formatCost(0.0001989, 'USD')).toBe('0,000199 USD');
  });
});

describe('formatMs', () => {
  it('passe en secondes au-dela de 1000 ms', () => {
    expect(formatMs(850)).toBe('850 ms');
    expect(formatMs(11367)).toBe('11,4 s');
    expect(formatMs(null)).toBe('—');
  });
});

describe('regression', () => {
  it('affiche un taux en pourcentage et un ecart toujours signe', () => {
    expect(formatRate(0.914)).toBe('91,4 %');
    expect(formatRate(null)).toBe('—');
    expect(formatPoints(-8.6)).toBe('−8,6 pts');
    expect(formatPoints(5)).toBe('+5 pts');
    expect(formatPoints(0)).toBe('±0 pts');
    expect(formatPercentDelta(12.5)).toBe('+12,5 %');
  });
});

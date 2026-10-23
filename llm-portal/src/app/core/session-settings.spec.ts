import { TestBed } from '@angular/core/testing';
import { SessionSettings } from './session-settings';

describe('SessionSettings', () => {
  beforeEach(() => sessionStorage.clear());

  function create(): SessionSettings {
    TestBed.resetTestingModule();
    return TestBed.inject(SessionSettings);
  }

  it("n'envoie rien quand la session est vide : le Gateway applique ses defauts globaux", () => {
    expect(create().requestOptions()).toEqual({
      provider: undefined,
      model: undefined,
      temperature: undefined,
      maxTokens: undefined,
      reasoning: undefined,
    });
  });

  it('traduit les champs saisis en options de requete', () => {
    const session = create();
    session.provider.set('deepseek');
    session.reasoning.set('off');
    session.maxTokens.set('2048');
    session.temperature.set('0,3');

    expect(session.requestOptions()).toMatchObject({
      provider: 'deepseek',
      reasoning: false,
      maxTokens: 2048,
      temperature: 0.3,
    });
  });

  it("survit au rechargement de l'onglet", () => {
    const session = create();
    session.reasoning.set('on');
    TestBed.tick();

    expect(create().reasoning()).toBe('on');
  });

  it('reinitialiser revient aux reglages globaux', () => {
    const session = create();
    session.maxTokens.set('512');

    session.reset();

    expect(session.customized()).toBe(false);
  });
});

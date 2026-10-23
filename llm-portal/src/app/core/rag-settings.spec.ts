import { TestBed } from '@angular/core/testing';
import { checkFile, MAX_UPLOAD_BYTES } from './rag-library';
import { DEFAULT_TOP_K, MAX_TOP_K, RagSettings } from './rag-settings';

describe('RagSettings', () => {
  beforeEach(() => sessionStorage.clear());

  function create(): RagSettings {
    TestBed.resetTestingModule();
    return TestBed.inject(RagSettings);
  }

  it('demarre en mode RAG, top-K 5, avec citations et extraits affiches', () => {
    const settings = create();

    expect(settings.ragMode()).toBe(true);
    expect(settings.topK()).toBe(DEFAULT_TOP_K);
    expect(settings.cite()).toBe(true);
    expect(settings.showSources()).toBe(true);
  });

  it('borne le top-K entre 1 et le maximum', () => {
    const settings = create();

    settings.setTopK(80);
    expect(settings.topK()).toBe(MAX_TOP_K);
    settings.setTopK(0);
    expect(settings.topK()).toBe(1);
    settings.setTopK(Number.NaN);
    expect(settings.topK()).toBe(1);
  });

  it('un nouveau document est interroge tant qu il n a pas ete decoche', () => {
    const settings = create();

    expect(settings.isIncluded('doc-1')).toBe(true);
    settings.toggleDocument('doc-1');
    expect(settings.isIncluded('doc-1')).toBe(false);
    settings.toggleDocument('doc-1');
    expect(settings.isIncluded('doc-1')).toBe(true);
  });

  it("survit au rechargement de l'onglet", () => {
    const settings = create();
    settings.ragMode.set(false);
    settings.toggleDocument('doc-1');
    TestBed.tick();

    const reloaded = create();
    expect(reloaded.ragMode()).toBe(false);
    expect(reloaded.isIncluded('doc-1')).toBe(false);
  });
});

describe('checkFile', () => {
  it('refuse un fichier vide ou de plus de 50 Mo avant tout envoi', () => {
    expect(checkFile(new File([], 'vide.pdf'))).toContain('vide');
    expect(checkFile({ size: MAX_UPLOAD_BYTES + 1 } as File)).toContain('50 Mo');
    expect(checkFile(new File(['%PDF-1.7'], 'ok.pdf'))).toBeNull();
  });
});

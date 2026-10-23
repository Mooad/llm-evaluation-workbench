# llm-rag

Prepare et retrouve la connaissance : ingestion de PDF, decoupage, embeddings, recherche.
**Il ne choisit pas le LLM et ne genere aucune reponse** : la generation reste dans `llm-gateway`
(`/chat`), le client assemble les deux.

Spring Boot 3.5, Java 17, Spring AI 1.1 (TokenTextSplitter, VectorStore Qdrant), Qdrant,
embeddings `bge-m3` via Ollama. Port **8082** (8081 = Gateway).

## Prerequis

```bash
docker run -d --name llm-rag-qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant
ollama pull bge-m3
```

Qdrant ou Ollama absent : l'application refuse de demarrer (la collection est creee au
demarrage, avec une dimension mesuree sur le modele).
Tableau de bord Qdrant : http://localhost:6333/dashboard

## Lancer

Les PDF de `data/` et `docs_rag/`, ainsi que les sources Markdown de
`scripts/test-corpus/`, sont locaux et ignores par Git. Le test d'integration qui
utilise `data/notice-sandero-jogger-fr.pdf` est ignore si ce fichier est absent.
Avec les sources Markdown locales, les PDF fictifs peuvent etre regeneres avec les
commandes de `scripts/README.md`.

```bash
mvn spring-boot:run          # port 8082
mvn test                     # le test Testcontainers est ignore si Docker est absent
```

## API

| Route | Role |
| --- | --- |
| `POST /documents` (multipart : `file`, `collection?`, `force?`) | 202 + `documentId`, statut `PROCESSING`, ingestion en arriere-plan. 200 + `alreadyKnown: true` si le meme contenu est deja indexe ou en cours (la `collection` est alors mise a jour sans reindexer). `force=true` reindexe un document pret. 415 si le format n'est pas gere |
| `GET /documents`, `GET /documents/{id}` | etat **par index** (`indexes.A`, `indexes.B` : statut, sections, chunks, erreur), statut d'ensemble, format, collection, fichier conserve ou non |
| `DELETE /documents/{id}` | 204 : retire le document de tous les index, son fichier et son entree du registre. 409 s'il est en cours d'indexation |
| `GET /indexes` | les index (strategies de decoupage) : collection, reglages, documents prets / en cours / en echec, chunks |
| `POST /indexes/{name}/rebuild` | 202 : reindexe tous les documents dans cet index a partir des fichiers conserves ; liste ceux sans fichier (a reimporter) |
| `POST /search` `{query, documentIds?, collections?, sections?, index?, topK=5}` | chunks les plus proches : score, localisation, section, citation, index, extrait et texte complet. Les filtres sont appliques par Qdrant, avant le classement |
| `GET /evaluations/dataset` | le jeu de questions d'evaluation |
| `POST /evaluations/retrieval?topK=5&scope=ALL\|COLLECTION&index=A` | mesure la recherche d'un index sur le jeu de questions (sans LLM) |
| `POST /evaluations/reports`, `GET /evaluations/reports`, `GET /evaluations/reports/{id}` | rapports d'evaluation (un JSON par execution dans `eval-reports/`) |

```bash
curl -F file=@data/notice-sandero-jogger-fr.pdf localhost:8082/documents
curl localhost:8082/documents/1238f494822a53b8
curl localhost:8082/search -H 'Content-Type: application/json' \
  -d '{"query":"Quelle est la pression des pneus ?","documentIds":["1238f494822a53b8"]}'
```

Collection Postman : `postman/llm-rag.postman_collection.json`.

- `documentIds` absent ou vide : recherche dans tous les documents.
- `score` : similarite cosine calculee par Qdrant (1 = identique). Un classement, pas une
  probabilite de pertinence : aucun seuil n'est applique a cette etape.
- `location` : ou citer le passage, selon la source. `{"type":"page","page":365}` pour un PDF,
  `{"type":"timeRange","startMs":134000,"endMs":168000}` pour une transcription (a venir).
  `citation` en donne la forme lisible : `notice-sandero-jogger-fr.pdf, page 365`.
- La page est celle **du fichier PDF** (1 = premiere page), pas le numero imprime sur la page.
  Sur la notice de `data/`, la page PDF 365 porte le numero imprime 363.
- `sections` (statut) : sections extraites avec du texte, soit les pages non vides pour un PDF
  (469 sur 472 pour la notice).

## Index : deux strategies de decoupage, cote a cote

Chaque document est indexe dans **tous** les index de `rag.indexes`, chacun dans sa collection
Qdrant : on compare deux strategies sur les memes documents, sans supprimer ni reindexer entre
deux mesures. Le premier index est celui par defaut (recherche, evaluation, anciens documents).

| Index | Strategie | Principe |
| --- | --- | --- |
| A | `token`, 400/80 | fenetres de 400 tokens, 80 de chevauchement, coupees ou que tombe la limite ; une page a la fois |
| B | `section`, 800 max | les pages consecutives d'une meme section du sommaire forment un chunk (plage de pages, « p. 222-224 »), coupe en debut de page au-dela de 800 tokens ; une page sans section reste seule |

- Les fichiers importes sont conserves (`state/files/`, hors git) : `POST /indexes/{name}/rebuild`
  reconstruit un index sans reimporter, apres un changement de strategie ou l'ajout d'un index.
- Un document importe avant la conservation des fichiers ne peut pas etre reconstruit : la
  reconstruction le signale, il faut le reimporter.
- `rag.qdrant.collection-prefix` (ex. `RAG_QDRANT_COLLECTION_PREFIX=eval-`) donne a une instance
  de test ses propres collections, sans toucher a la liste des index.
- Un registre ecrit avant les index multiples est migre au demarrage : ses documents sont
  rattaches a l'index A (collection `llm-rag-chunks`), l'index B est a reconstruire.

## Evaluation

Jeu de 35 questions (`src/main/resources/evaluation/rag-dataset.json`) sur les documents de
`docs_rag/` : pour chacune, la page qui contient la reponse et les faits attendus (`"45 jours"`,
`"a|b"` = l'une des formes). Categories : chiffre, procedure, variante (deux versions d'un
produit), multilingue (question en francais, document en anglais), hors corpus (3 questions
sans reponse dans les documents : le bon comportement est de le dire).

`docs_rag/10` a `19` sont **fictifs** (generes par `scripts/GenerateTestCorpus.java` depuis
`scripts/test-corpus/*.md`) : aucun LLM ne peut connaitre leurs reponses, ce qui rend visible
l'apport du RAG. Les autres sont reels (notice Dacia, article RAG, NIST CSF 2.0, GIEC).

`POST /evaluations/retrieval` mesure la **recherche seule**, sans LLM :

| Mesure | Question posee |
| --- | --- |
| Recall@1 | la page de la reponse est-elle le premier extrait ? |
| Recall@K | fait-elle partie des K extraits envoyes au modele ? Sinon, il ne peut pas bien repondre |
| MRR | moyenne de 1/rang : 1 = toujours premiere |
| Couverture des variantes | pour une reponse en plusieurs variantes, chaque variante est-elle retrouvee ? |
| Precision documentaire | part des extraits qui viennent du bon document (le reste est du bruit) |
| Score moyen du 1er extrait | avec reponse / hors corpus : si proches, aucun seuil ne repere le hors-sujet |

`scope=COLLECTION` cherche seulement dans la collection du document attendu, comme un
utilisateur qui a choisi la bonne collection : c'est la mesure du filtre par metadonnees.
La generation (reponses avec et sans RAG, LLM juge) est orchestree par le portail, onglet
**Evaluation RAG**, qui enregistre le rapport complet ici.

### Mesures (2026-10-23, bge-m3, chunks 400/80, top 5, 32 questions avec reponse)

| | Sans sections | Section dans l'embedding | **Section hors embedding** | + filtre collection |
| --- | --- | --- | --- | --- |
| Recall@1 | 87,5 % | 87,5 % | 87,5 % | 87,5 % |
| Recall@5 | 100 % | 96,9 % | **100 %** | 100 % |
| Couverture des variantes | 100 % | 100 % | 100 % | 100 % |
| Precision documentaire | 80,0 % | 82,5 % | 80,0 % | **84,4 %** |

- Mettre le titre de section dans l'embedding fait perdre une question (NIST) : les titres de
  sommaire sont parfois vagues (« Tableau de bord E », « 1. CSF Overview ») et tirent le chunk
  vers son chapitre. La section reste stockee : elle sert au filtre et au prompt, ou elle aide
  le modele a distinguer deux variantes. Reglage : `rag.embedding.section-in-embedding`.
- Le filtre par collection retire du bruit (precision 80 → 84,4 %) sans rien faire perdre.
- La question piege « offre Premium de Moviid » (inexistante) obtient 0,75, plus que la
  moyenne des vraies reponses (0,71) : un seuil de score ne peut pas detecter le hors-sujet.

## Architecture

```
api/            controllers, DTO, erreurs
ingestion/      IngestionService (empreinte, doublons, asynchrone) -> IngestionPipeline (generique)
  source/       SourceFile, DocumentReader, SourceFormat (reader + chunker), SourceFormats (choix du format)
    pdf/        PdfDocumentReader
  chunking/     Chunker, TokenChunker (A), SectionChunker (B)
index/          SearchIndex (strategie + collection + pipeline), SearchIndexes
chunk/          modele commun : Chunk, SourceLocation (PageLocation | PageRangeLocation | TimeRangeLocation), SearchFilter, ChunkStore (port)
store/qdrant/   QdrantChunkStore (adaptateur), QdrantPayload (format du payload), SpringAiEmbeddingAdapter
embedding/      EmbeddingClient (port), OllamaEmbeddingClient
search/         SearchService (documents, collections, sections)
evaluation/     EvalDataset, RetrievalEvaluator, RetrievalReport, ReportStore
document/       DocumentRegistry (catalogue persiste, etat par index), FileStore (fichiers conserves)
config/         RagConfig : tout le branchement
```

Le pipeline ne connait que des interfaces :

```
SourceFile --SourceFormats.resolve--> SourceFormat
   reader.read   -> ExtractedDocument (sections localisees)
   chunker.chunk -> ChunkDraft[]
   pipeline      -> Chunk[] (documentId, index continu, identifiant stable)
   ChunkStore.replace (embeddings + ecriture)
```

| Motif | Ou | Pour quoi |
| --- | --- | --- |
| Strategie | `DocumentReader`, `Chunker` | un format = une facon de lire et de decouper, interchangeables |
| Registre / resolution | `SourceFormats` | choisir le format sur le contenu (signature), pas sur le nom |
| Port et adaptateur | `ChunkStore` / `QdrantChunkStore`, `EmbeddingClient` / `OllamaEmbeddingClient` | changer de base (Atlas pour Moviid) ou passer par le Gateway sans toucher au pipeline |
| Adaptateur | `SpringAiEmbeddingAdapter` | brancher notre `EmbeddingClient` dans le VectorStore de Spring AI |
| Type scelle | `SourceLocation` | une page ou un intervalle de temps, jamais un champ `page` vide pour une video |

**Ajouter un format** (transcription Moviid, HTML...) :
1. un `DocumentReader` : `supports` (signature du contenu) et `read` (sections + localisation) ;
2. un `Chunker` si le decoupage par tokens ne convient pas (fenetres de temps pour une video) ;
3. une ligne dans `RagConfig.sourceFormats`.

Rien d'autre ne change : ni le pipeline, ni le stockage, ni la recherche, ni l'API.
`IngestionPipelineTest` le montre avec un format de transcription factice.

## Pipeline PDF

- **Sections** : titre de chaque page lu dans le sommaire du PDF (signets, le plus profond
  l'emporte) ; sans sommaire, aucune section n'est inventee.
- **Lecture** : PDFBox directement. Le `PagePdfDocumentReader` de Spring AI echoue sur la notice
  (`Unreasonable number of lines`) ; l'extracteur standard de PDFBox lit chaque colonne d'un bloc.
- **Decoupage** : `rag.chunking.chunk-size` (400) et `overlap` (80), en tokens cl100k.
  `TokenTextSplitter` n'a pas de chevauchement : on decoupe en `taille - chevauchement`, puis
  on ajoute devant chaque chunk la fin du precedent. Un chunk ne traverse jamais une page.

## Commun a tous les formats

- **Embeddings** : derriere l'interface `EmbeddingClient`. Aujourd'hui `OllamaEmbeddingClient`
  (`POST /api/embed`) ; demain `POST /embeddings` du Gateway, en changeant un seul bean (`RagConfig`).
- **Empreinte** : SHA-256 du fichier ; `documentId` = ses 16 premiers caracteres. Le meme PDF,
  meme renomme, n'est pas indexe deux fois.
- **Idempotence** : l'identifiant d'un point Qdrant derive de `documentId` + `chunkIndex`.
  Reindexer ecrase les memes points, puis supprime ceux en trop : pas de doublon, meme apres
  un redemarrage.
- **Collections** : groupe de documents choisi a l'import, stocke dans le registre. La
  recherche traduit une collection en `documentIds`, filtres par Qdrant : changer la
  collection d'un document ne demande aucun recalcul.
- **Payload Qdrant** : `documentId`, `chunkIndex`, `fichier`, `sourceType`, `section`, `locationType` et
  les champs de la localisation (`page`, ou `startMs`/`endMs`). Les points ecrits avant
  `locationType` sont relus comme des pages, sans reindexation.
- **Registre** : le catalogue des documents (statut, empreinte, pages, chunks) est dans
  `state/documents.json` (`rag.registry.file`, hors git), reecrit a chaque changement
  (fichier temporaire puis renommage atomique). Au redemarrage il est relu : la liste est
  intacte et un PDF deja indexe est reconnu sans recalcul. Qdrant ne connait que les chunks ;
  le lien chunk -> document est le champ `documentId` de chaque chunk.
  - Un document encore `PROCESSING` au redemarrage passe `FAILED` (ingestion interrompue) :
    le reimporter relance l'ingestion.
  - Fichier illisible : l'application refuse de demarrer plutot que d'ecraser le catalogue.

Chaque ingestion ecrit une ligne `rag.ingestion` (cle=valeur) : format, sections, chunks,
reglages du chunker, modele, temps de lecture, temps d'embedding + ecriture. Notice de
472 pages : ~1 s de lecture, ~47 s d'embedding (780 chunks, bge-m3 local).

## Limites connues

- Le registre et Qdrant peuvent diverger : supprimer le conteneur Qdrant (`docker rm`, sans
  volume) efface les chunks, alors que le registre dit toujours `READY`. Supprimer alors
  `state/documents.json`, ou monter un volume : `-v ./qdrant_storage:/qdrant/storage`.
- L'indexation fait un index apres l'autre : importer un document prend le temps de chaque
  strategie (notice : environ 47 s pour A, 62 s pour B).
- Un seul processus par fichier de registre : deux instances lancees depuis le meme dossier
  s'ecraseraient le fichier.
- PDF scannes (images sans texte) : aucune page lue, pas d'OCR.
- Pas d'authentification (usage local).

## Hors perimetre (etapes suivantes)

Toggle RAG dans le portail, injection des chunks dans `/chat/stream`, `POST /embeddings` dans le
Gateway, evaluation RAG ON/OFF.

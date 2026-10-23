# Passation — LLM Gateway + LLM Portal

Etat reel au 2026-10-16. Lire en entier avant de coder.

## 1. Contexte

Moad, ingenieur backend senior (Java, Spring, Kubernetes ; produit Moviid : transcodage video
et sous-titrage), suit un plan de 6 mois pour concevoir, evaluer et deployer des systemes IA.
Il apprend de son cote ; l'IA **construit**. Repondre en francais, court et direct.
Plan : `~/Desktop/ai_mastery_roadmap.html` (aussi dans `ai-applied-mastery/`).

| Depot | Role | Git |
| --- | --- | --- |
| `AI Projects/llm-gateway` | le produit (Spring Boot 3.2, Java 17) | remote `github.com/Mooad/llm-gateway`, **1 seul commit, ~100 fichiers non commites** |
| `AI Projects/llm-portal` | console de test (Angular 22, zoneless, signals, SCSS) | **pas encore de depot** |

## 2. Lancer

```bash
# Gateway (port 8081)
cd ~/Documents/My-Projects/"AI Projects"/llm-gateway && mvn -o spring-boot:run
mvn -o test                          # 150 tests
scripts/eval-gate.sh ollama          # non-regression (voir 6)

# Portail (port 4200, relaie /api vers 8081)
cd ~/Documents/My-Projects/"AI Projects"/llm-portal
PATH="$HOME/.local/share/node/current/bin:$PATH" npm start   # npm test : 13 tests
```

- Cles API : `application-secrets.yml` (ignore par git) pour deepseek et openai.
- Node 24 est installe a part dans `~/.local/share/node/current` ; le Node 18 global reste intact.
- Moad fait souvent tourner sa propre instance (IntelliJ sur 8081, `npm start` sur 4200) :
  **ne pas la tuer**, tester sur 8091 / 4300 (`GATEWAY_URL=http://localhost:8091 npx ng serve --port 4300`).

## 3. Regles non negociables

1. **Sans memoire** : aucune conversation stockee, le client renvoie tout l'historique.
2. **Aucune logique metier ni RAG** dans le Gateway : il donne acces aux modeles, c'est tout.
3. **Il mesure, il ne decide pas** : chaque mesure dit comment elle a ete obtenue.

## 4. Architecture (Gateway)

```
api/            controllers + DTO
core/           ChatService, LlmProvider (le port), ProviderRegistry, FallbackManager,
                ProviderGuard (retry + circuit), FailureClassifier, AppliedOptions, CostEstimator
provider/       ollama/ ; openai/OpenAiCompatibleProvider -> deepseek/, openai/OpenAiProvider
structured/     SchemaSupport (refus amont), SchemaValidator, SchemaPromptBuilder
eval/           EvalRunner, EvalJobs, DatasetCatalog, ReportStore, CaseFingerprint,
                scoring/ (regles, juge LLM), regression/RegressionAnalyzer, gate/ (non-regression)
observability/  CallLogger, CallContext (request_id, source), RequestIdFilter
config/         proprietes, FileGenerationDefaults (settings/defaults.json)
```

Points a connaitre :

- **Fournisseurs** : ollama (local, defaut, `qwen3-coder:30b`), deepseek (`deepseek-flash`),
  openai (`gpt-4.1-mini`). Ajouter un fournisseur = une classe `@Component` ; un fournisseur
  au format OpenAI = une sous-classe d'`OpenAiCompatibleProvider` de 20 lignes.
- **Repli** : ordre explicite `gateway.fallback-order` (l'ordre de la map `providers` n'est pas
  fiable). Retry 1 fois sur panne passagere, circuit ouvert apres 3 pannes. Strategie complete :
  `docs/fallback-strategy.md`. En streaming : ni repli ni retry apres le premier fragment.
- **Reglages** : requete > global (`settings/defaults.json`, editable depuis le portail) >
  defaut fournisseur. Chaque reponse expose `applied`, avec l'origine de chaque reglage.
- **Pieges modeles** :
  - le raisonnement DeepSeek consomme `max_tokens`, il est donc desactive par defaut (global) ;
  - les `gpt-5.x` refusent `temperature: 0` ;
  - OpenAI renvoie une version datee du modele, le cout prend le tarif de la famille.
- **Cout** : table `gateway.pricing` ; un modele absent = cout « inconnu », jamais 0.
  Le local est a 0 (energie non comptee).
- **Logs de cout** : `logs/gateway.log`, une ligne `cle=valeur` par appel, avec `request_id` et
  `source` (`api`/`eval`/`eval-judge`). Format documente dans le README.

## 5. Endpoints

| Route | Role |
| --- | --- |
| `POST /chat`, `/chat/stream` (SSE), `/chat/structured` | generation ; streaming ; sortie validee par JSON Schema (422 si non conforme, 400 si mot-cle non gere) |
| `GET /providers`, `/providers/status` | fournisseurs, sondes, etat des circuits |
| `GET/PUT /settings`, `GET /pricing` | defauts globaux, tarifs |
| `POST /eval/run` (synchrone), `/eval/runs` (asynchrone, progression, annulation) | evaluation, `systemPrompt` optionnel |
| `GET/POST /eval/datasets`, `GET/DELETE /eval/reports`, `POST /eval/regressions` | datasets, rapports, etat de regression |

Collection Postman : `postman/llm-gateway.postman_collection.json` (34 requetes).

## 6. Evaluation

- Golden dataset : 35 cas dont 5 avec schema (`src/main/resources/evaluation/dataset.json`).
  `GoldenDatasetTest` exige une reponse ideale pour tout nouveau cas.
- 3 dimensions notees separement : CORRECTNESS, FORMAT, HALLUCINATION. Une regle d'abord, le
  juge LLM ensuite (le juge coupe le raisonnement).
- **Regression** : comparaison sur les cas communs **et identiques** (empreinte `caseHash`), en
  pourcentages ; seuil de 5 pts ; cout par reponse juste ; rapport JSON dans `eval-reports/regressions/`.
- **Non-regression** : `scripts/eval-gate.sh` (regles seules, temperature 0) compare a la reference
  versionnee `eval-baselines/` (Ollama : 95 % correctness) ; il echoue en cas de REGRESSION.
  Il se lance a la main ; un hook `pre-push` a ete juge non urgent.
- Portail, onglet Evaluations : lancer, suivre, lire un rapport, comparer deux executions.

## 7. Ou en est le plan

- **Mois 1 et 2 (semaines 1 a 8) : faits**. Gateway, fallback, sorties structurees, streaming,
  portail, evaluation, regression, resilience, prompt systeme en evaluation.
- **A faire, semaine 8** :
  - mesurer l'effet d'un prompt d'abstention sur le dataset (une execution sans, une avec, puis
    comparer) ; premier signal positif sur `hallucination_005` ;
  - **design d'ingestion RAG**, en cours cote Moad.
- **Design RAG retenu** :
  - un **service `rag-service` separe** (Spring AI + pgvector) pour l'ingestion et la recherche ;
  - le Gateway gagne seulement `POST /embeddings`, et la generation passe par `/chat` ;
  - embeddings multilingues FR/AR (bge-m3 ou multilingual-e5 via Ollama) ;
  - mise a jour d'une source par empreinte du contenu ;
  - Moad prepare d'abord la liste des donnees a ingerer.

## 8. Dettes, par priorite

1. **Injection via le schema** : le schema du client va dans un message `system`. Le deplacer
   cote `user`, et limiter sa taille et sa profondeur.
2. **Cout local a 0** : estimer l'energie (W x duree x prix du kWh). Valeurs a demander a Moad.
3. **`allowFallback: false` appelle quand meme le repli** (facture en evaluation).
4. **Commits** : ~100 fichiers non commites dans le Gateway ; portail sans depot git.
5. Etat en memoire : circuits et executions d'evaluation perdus au redemarrage ; un circuit par
   fournisseur, pas par modele.
6. Cout DeepSeek approximatif (ni heures creuses ni cache) ; pas d'identite de l'appelant dans les logs.
7. `PUT /settings` sans authentification (acceptable en local seulement).
8. Note d'architecture jamais ecrite ; lib client typee pour les sorties structurees (module a part).

## 9. Conventions

- Java : code et commentaires en francais **sans accents** ; les commentaires disent **pourquoi**.
- Tests nommes en phrase : `bascule_sur_le_fournisseur_suivant_quand_le_premier_tombe`.
- Records pour les objets de transport ; injection par constructeur.
- Un nouvel endpoint = une requete Postman avec ses assertions.
- Portail : composants standalone a signaux, tokens CSS dans `styles.scss`, aucune librairie d'UI.
- Verifier en vrai (curl, navigateur) avant de dire « fait » ; ne pas commiter sans demande.

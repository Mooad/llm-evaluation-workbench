# LLM Gateway

Point d'entree unique vers les fournisseurs LLM.

Toutes les applications parlent au Gateway. Le Gateway parle aux fournisseurs.
Personne d'autre n'appelle Ollama ou DeepSeek directement.

Trois fournisseurs, dans l'ordre de repli (`gateway.fallback-order`) : **Ollama** en local
(gratuit, par defaut), puis **DeepSeek** et **OpenAI** a distance (payants). Les deux distants
partagent un seul adaptateur au format OpenAI (`OpenAiCompatibleProvider`).

## Pourquoi

- **Changer de fournisseur** sans toucher au code appelant
- **Savoir ce que ca coute** : un seul endroit qui compte les tokens et la latence
- **Gerer les pannes** : timeout, retry, bascule (a venir)
- **Appliquer une politique** : temperature imposee, format verifie (a venir)

## Perimetre

Ce qu'il fait : l'acces aux modeles.
Ce qu'il ne fait pas : le metier, le RAG, l'agent, l'execution d'outils.

| Composant | Role | Ou |
| --- | --- | --- |
| Gateway | Acces aux **modeles** | ici |
| Chatbot Angular | Client du Gateway | mois 3 |
| Service RAG | Appelle le Gateway | mois 3 |
| Agent DevOps | Appelle le Gateway | mois 4 |
| Serveur MCP | Acces aux **outils** | mois 4 |

Le Gateway **transmet** les definitions d'outils au modele (c'est dans l'API des
fournisseurs). Il ne les **execute** pas : c'est le role de l'agent.

Regle : si ca n'a pas de rapport avec l'acces aux modeles, ca ne rentre pas.

## Trois decisions structurantes

1. **Sans memoire.** Aucune conversation stockee. Le client renvoie l'historique
   complet a chaque appel.
2. **Aucune connaissance metier.** Pas d'endpoint "resume de video". Il expose `/chat`.
3. **Il mesure, il ne decide pas.** C'est ce qui alimentera le systeme d'evaluation.

## Demarrer

Prerequis : Java 17, Maven, et Ollama qui tourne en local.

```bash
ollama serve            # dans un autre terminal si besoin
mvn spring-boot:run
```

Pour utiliser DeepSeek ou OpenAI, creer le fichier local de secrets a partir du modele
versionne (sans ecraser un fichier deja present) :

```bash
cp -n application-secrets.example.yml application-secrets.yml
```

Remplir ensuite les champs `api-key` necessaires dans `application-secrets.yml` :

```yaml
gateway:
  providers:
    deepseek:
      api-key: ""
    openai:
      api-key: ""
```

Le fichier `application-secrets.yml` est ignore par Git. Laisser une cle vide si
ce fournisseur n'est pas utilise. Autre possibilite : ne pas creer ce fichier et
definir `DEEPSEEK_API_KEY` et/ou `OPENAI_API_KEY` dans l'environnement.
Sans cle, le Gateway demarre quand meme et `/providers/status` indique le
fournisseur distant comme `DEGRADED`.

L'application ecoute sur le port **8081** (8080 etait occupe).

### Appeler /chat

```bash
curl -s -X POST http://localhost:8081/chat \
  -H "Content-Type: application/json" \
  -d '{
    "messages": [
      {"role": "system", "content": "Tu reponds en une phrase courte."},
      {"role": "user", "content": "Quelle est la capitale de la Mauritanie ?"}
    ],
    "temperature": 0.2,
    "maxTokens": 40
  }'
```

Reponse :

```json
{
  "content": "La capitale de la Mauritanie est Nouakchott.",
  "provider": "ollama",
  "model": "qwen3-coder:30b",
  "inputTokens": 38,
  "outputTokens": 15,
  "latencyMs": 16614,
  "finishReason": "stop"
}
```

### Streaming — `POST /chat/stream`

Meme corps que `/chat`, reponse en Server-Sent Events : `delta` (un fragment de texte),
puis `done` (tokens, `firstTokenMs`, `latencyMs`, cout et `costBasis`) ou `error`.
Le fallback ne joue que tant qu'aucun fragment n'est parti. Client de test : `llm-portal`.

```bash
curl -sN -X POST http://localhost:8081/chat/stream -H "Content-Type: application/json" \
  -d '{"provider":"ollama","messages":[{"role":"user","content":"Cite trois codecs video."}]}'
```

### Sortie structuree — `POST /chat/structured`

L'appelant fournit un JSON Schema ; le Gateway l'ajoute au prompt puis **valide** la
reponse : `200` si conforme, `422` sinon (avec `violations` et la sortie brute).
Le schema verrouille la forme, jamais le contenu.

Mots-cles verifies : `type` (une seule valeur), `enum`, `minimum`, `maximum`, `minLength`,
`maxLength`, `required`, `properties`, `additionalProperties` (booleen), `items` (un schema).
Acceptes car purement descriptifs : `$schema`, `title`, `description`, `examples`.
**Tout autre mot-cle** (`pattern`, `format`, `minItems`, `oneOf`...) donne un `400 invalid_schema`
qui le nomme avec son chemin, **avant** tout appel au modele : aucun token consomme.

### Reglages globaux et tarifs — `/settings`, `/pricing`

Trois reglages par fournisseur — `reasoning`, `maxTokens`, `temperature` — se resolvent dans
cet ordre : **requete** > **global** (`settings/defaults.json`) > **defaut du fournisseur**.
En repli, chaque fournisseur recoit ses propres defauts. Chaque reponse (`/chat`, evenement
`done` de `/chat/stream`) porte `applied` : la valeur envoyee et son origine
(`REQUEST`, `GLOBAL`, `PROVIDER`).

| Methode | Route | Role |
| --- | --- | --- |
| `GET` | `/settings` | defauts globaux par fournisseur, et le fichier ou ils vivent |
| `PUT` | `/settings/{fournisseur}` | les remplace et reecrit le fichier (`400 invalid_settings` si `reasoning` sur un fournisseur qui ne le gere pas) |
| `GET` | `/pricing` | table de prix appliquee (lecture seule, source `application.yml`) |

Aucune authentification sur `PUT /settings` : acceptable en local, a proteger avant toute
exposition reseau.

### Lister les fournisseurs

```bash
curl -s http://localhost:8081/providers
```

## Architecture

```
  POST /chat
      |
      v
  ChatController          traduit DTO <-> domaine, valide l'entree
      |
      v
  ChatService             resout, appelle, mesure, journalise
      |
      +--> ProviderRegistry     nom -> implementation
      +--> CallLogger           latence ET tokens, jamais l'un sans l'autre
      |
      v
  LlmProvider  (interface)      <-- LE point d'extension
      |
      +--> OllamaProvider       modele local, sans cle API
      +--> OpenAiCompatibleProvider   format OpenAI, cle API (abstrait)
              +--> DeepSeekProvider    + extension "thinking"
              +--> OpenAiProvider      + max_completion_tokens
```

### Le port `LlmProvider`

```java
public interface LlmProvider {
    String name();
    String defaultModel();
    ChatResult chat(ChatCall call);
}
```

Ajouter un fournisseur = ajouter une classe `@Component` qui implemente cette
interface. Spring l'injecte automatiquement dans le `ProviderRegistry`. Aucune
modification du controller ni du service.

### Le domaine ne connait aucun fournisseur

`Message`, `ChatCall`, `ChatResult`, `Usage` ne contiennent aucun terme propre a
Ollama ou DeepSeek. Chaque adaptateur traduit dans les deux sens. C'est ce qui
rend le remplacement d'un fournisseur indolore.

## Configuration

Tout est dans `src/main/resources/application.yml`. Changer de modele ou de
fournisseur ne demande **aucune** modification de code.

```yaml
gateway:
  default-provider: ollama
  providers:
    ollama:
      base-url: http://localhost:11434
      default-model: qwen3-coder:30b
      timeout-seconds: 180
    deepseek:
      base-url: https://api.deepseek.com
      default-model: deepseek-flash
      api-key: ${DEEPSEEK_API_KEY:}
      timeout-seconds: 60
```

Priorite du modele : celui de la requete, sinon celui du fournisseur en configuration.
En repli, chaque fournisseur utilise **son** modele par defaut, jamais celui demande au primaire.

Le Gateway refuse de demarrer si `default-provider` designe un fournisseur qui
n'est pas charge. Echouer au demarrage vaut mieux qu'echouer a la premiere requete.

## Journalisation et analyse de cout

Chaque appel a un fournisseur produit **une ligne** sur le logger `gateway.calls`, au
format `cle=valeur` separe par des espaces, dans la console et dans `logs/gateway.log`
(rotation 10 Mo, 7 jours). Un fallback produit une ligne par tentative, toutes avec le
meme `request_id`.

```
request_id=3f1c...e9 source=api provider=deepseek model=deepseek-flash status=ok input_tokens=37 output_tokens=25 latency_ms=1167 finish_reason=COMPLETE provider_finish_reason=stop cost=4.11E-5 currency=USD
request_id=3f1c...e9 source=api provider=ollama model=qwen3-coder:30b status=error latency_ms=13 error_type=ProviderCallException error_message=Appel Ollama en echec : I/O error ...
```

### Champs d'une ligne `status=ok`

| Champ | Contenu | Comment c'est obtenu |
| --- | --- | --- |
| `request_id` | requete HTTP d'origine, `-` hors requete | en-tete `X-Request-Id` de l'appelant s'il est sur (`[A-Za-z0-9._-]{1,64}`), sinon UUID ; renvoye dans la reponse |
| `source` | `api`, `eval` (candidat) ou `eval-judge` (juge) | pose par `EvalRunner` et `LlmJudge`, `api` sinon |
| `provider` | fournisseur qui a **reellement** servi | nom de l'adaptateur |
| `model` | modele **demande** au fournisseur | requete, sinon `default-model` |
| `status` | `ok` | |
| `input_tokens` | tokens d'entree, `-` si non rapportes | compteur du fournisseur (`prompt_eval_count` Ollama, `prompt_tokens` DeepSeek) |
| `output_tokens` | tokens generes, `-` si non rapportes | compteur du fournisseur (`eval_count`, `completion_tokens`) — raisonnement DeepSeek **inclus** |
| `latency_ms` | duree de l'appel | horloge du Gateway, envoi → reponse complete |
| `finish_reason` | `COMPLETE`, `TRUNCATED`, `REFUSED`, `TOOL_USE`, `UNKNOWN` | normalise par `FinishReason` |
| `provider_finish_reason` | valeur brute, `-` si absente | fournisseur |
| `cost` | montant estime, `-` si **inconnu** | `(in x prix_in + out x prix_out) / 1M`, table `gateway.pricing` |
| `currency` | devise de `cost` | `gateway.pricing.currency` |

### Champs d'une ligne `status=error`

`request_id`, `source`, `provider`, `model`, `status=error`, `latency_ms`, `error_type` (classe Java),
`error_message` (**toujours en dernier** : il contient des espaces, prendre la fin de ligne).
Aucun token ni cout n'est logge : on suppose l'appel non facture (voir limites).

### Regles de lecture

- **`cost=-` ne veut pas dire gratuit.** Zero = modele local ; `-` = tarif absent de
  `gateway.pricing` ou tokens non rapportes. Ne jamais sommer `-` comme 0 sans le compter a part.
- **Le tarif est cherche avec le modele renvoye par le fournisseur**, pas avec `model`
  (le modele demande). Les deux coincident pour Ollama et DeepSeek aujourd'hui.
- **Comparer des couts, jamais des tokens entre fournisseurs** : chaque tokenizer
  compte differemment le meme texte.
- **Le cout DeepSeek est une borne haute** : tarif heure pleine, entree hors cache.
- `cost` peut sortir en notation scientifique (`4.11E-5`) : `awk` et Python le lisent tel quel.
- La latence **seule** n'est pas interpretable : "22 secondes" ne dit pas si le modele est
  lent ou si l'entree etait enorme. C'est mesure : entree x23 → latence x26.

### Agreger

Cout, tokens et appels par fournisseur. Pour exclure l'evaluation, ajouter
`| grep 'source=api'` apres le premier `grep` ; pour suivre une requete, `grep 'request_id=<id>'`.

```bash
grep 'gateway.calls' logs/gateway.log | sed -E 's/.*(request_id=)/\1/' | awk '
  { delete f; for (i = 1; i <= NF; i++) { split($i, kv, "="); f[kv[1]] = kv[2] }
    p = f["provider"]; n[p]++
    if (f["status"] == "ok") { ok[p]++; tin[p] += f["input_tokens"]; tout[p] += f["output_tokens"]
      if (f["cost"] != "-") c[p] += f["cost"]; else unk[p]++ } }
  END { for (p in n) printf "%-9s appels=%d ok=%d in=%d out=%d cout=%.6f cout_inconnu=%d\n",
        p, n[p], ok[p], tin[p], tout[p], c[p], unk[p] }'
```

```
ollama    appels=28 ok=0 in=0 out=0 cout=0.000000 cout_inconnu=0
deepseek  appels=27 ok=27 in=1837 out=3191 cout=0.004380 cout_inconnu=0
```

### Limites actuelles

- **Pas d'identite de l'appelant** : impossible de repartir le cout par application
  (chatbot, RAG, agent). Contournement : un prefixe dans `X-Request-Id` (`chatbot-...`).
- **`logs/gateway.log` est relatif au repertoire de lancement** : lancer depuis la racine du projet.
- **Timeout cote Gateway** : le fournisseur a pu generer (et facturer) une reponse que
  le Gateway a abandonnee. La ligne `error` n'a alors aucun cout.
- **Cache d'entree DeepSeek ignore** (`prompt_cache_hit_tokens`), d'ou la borne haute.

## Erreurs

| Situation | Statut | Code |
| --- | --- | --- |
| Fournisseur inconnu | 400 | `unknown_provider` |
| Requete invalide | 400 | `invalid_request` |
| Schema avec un mot-cle non verifie | 400 | `invalid_schema` |
| Reglage global sans effet (raisonnement sur Ollama) | 400 | `invalid_settings` |
| Dataset d'evaluation invalide | 400 | `invalid_dataset` |
| Sortie non conforme au schema | 422 | — (`violations` dans le corps) |
| Fournisseur injoignable | 502 | `provider_unavailable` |
| Toute la chaine de fallback a echoue | 502 | `all_providers_failed` |

## Tests

```bash
mvn test
```

## Evaluation — `POST /eval/run`

Rejoue le golden dataset (`src/main/resources/evaluation/dataset.json`) contre un
fournisseur et note chaque reponse sur trois dimensions, **separement** :
`CORRECTNESS`, `FORMAT`, `HALLUCINATION`. La latence et les tokens sont mesures sur chaque cas.

```bash
curl -s -X POST http://localhost:8081/eval/run -H "Content-Type: application/json" \
  -d '{"provider":"ollama","caseIds":["math_001","hallucination_001"]}'
```

| Champ | Defaut | Role |
| --- | --- | --- |
| `provider` / `model` | fournisseur par defaut | le candidat |
| `caseIds` | tous | iterer vite sur quelques cas |
| `useJudge` | `true` | `false` = regles seules, rapide |
| `judgeProvider` / `judgeModel` | le candidat | le juge LLM |
| `temperature` | `0.0` | reproductibilite |
| `maxTokens` | `512` | |
| `allowFallback` | `false` | un cas servi par un repli est **exclu** |
| `datasetPath` | config | `classpath:...` ou `file:/chemin/absolu.json` |

`GET /eval/dataset` verifie qu'un dataset se charge, sans appeler de modele.

### Executions, datasets, rapports, regression

| Methode | Route | Role |
| --- | --- | --- |
| `GET` | `/eval/datasets` | golden integre + datasets importes, avec empreinte et categories |
| `GET` | `/eval/datasets/{id}/cases` | les cas d'un dataset |
| `POST` | `/eval/datasets/{id}` | importe un dataset (corps = le tableau JSON), valide avant ecriture, jamais d'ecrasement |
| `POST` | `/eval/runs` | lance en arriere-plan (202) ; **une seule a la fois** (409 `eval_running`) pour ne pas fausser les latences |
| `GET` | `/eval/runs/{id}` | progression (`done`/`total`, dernier cas), puis `reportId` |
| `POST` | `/eval/runs/{id}/cancel` | annule apres le cas en cours ; aucun rapport n'est ecrit |
| `GET` | `/eval/reports`, `/eval/reports/{id}` | historique (resume) et rapport complet |
| `DELETE` | `/eval/reports/{id}` | supprime un rapport |
| `POST` | `/eval/regressions` | etat de regression entre deux rapports, ecrit dans `eval-reports/regressions/` |

`POST /eval/run` (synchrone) reste pour le CLI.

### Etat de regression

Deux rapports se comparent **sur les cas communs et identiques** : meme id et meme empreinte
(`caseHash`, calculee sur le contenu du cas). Un cas ajoute, retire ou modifie est liste a part,
jamais compte : un dataset qui grossit ne fabrique ni fausse regression ni faux progres.

Par dimension, les deux taux sont calcules **sur les memes cas** (notes PASS ou FAIL des deux
cotes) et l'ecart est en points de pourcentage :

| Etat | Regle (seuil `gateway.eval.regression-threshold-points`, 5 par defaut) |
| --- | --- |
| `REGRESSION` | une dimension baisse de plus que le seuil |
| `IMPROVEMENT` | aucune baisse au-dela du seuil, au moins une hausse au-dela |
| `STABLE` | tout dans le seuil — les cas qui ont bascule restent listes |
| `NOT_COMPARABLE` | aucun cas commun note des deux cotes |

Le rapport de regression donne aussi le cout (total, par cas, **par reponse juste**) et la
latence (p50, moyenne) sur les cas communs, et signale juges ou reglages differents.

### Comment chaque dimension est notee

Regle deterministe d'abord, juge LLM seulement quand aucune regle ne sait trancher.
Chaque verdict indique sa methode (`regle` ou `juge:<fournisseur>/<modele>`).

| Dimension | Par regle | Par le juge |
| --- | --- | --- |
| Correctness | nombre exact, texte court contenu, objet JSON champ par champ | sens attendu, critere d'acceptation |
| Format | nombre seul, champs JSON exacts, nombre de phrases, liste de N elements ; **cas a schema : verdict de `SchemaValidator` (methode `schema`)** | langue |
| Hallucination | — | toujours |

Verdicts : `PASS`, `FAIL`, `SKIPPED` (non demande, ou juge desactive), `ERROR` (impossible
de conclure). Le `passRate` = PASS / (PASS + FAIL) : ce qu'on n'a pas pu juger n'est ni
une reussite ni un echec.

### Cas a schema

Un cas avec un champ `schema` passe par `/chat/structured` au lieu de `/chat` : c'est la
seule facon de mesurer `SchemaValidator`. `FORMAT` = conforme ou non au schema ;
`CORRECTNESS` reste la comparaison champ par champ avec `expected_answer`. Une sortie
**conforme mais fausse** sort donc `FORMAT PASS` / `CORRECTNESS FAIL`. Un schema que le
Gateway ne sait pas verifier fait refuser le dataset au chargement.

Le rapport donne `schemaConformity` : par fournisseur qui a repondu, cas a schema mesures,
cas conformes, taux. Les cas exclus (repli) ou sans reponse n'y entrent pas.

### Pieges signales dans `warnings`

- **Juge = candidat** : le modele se note lui-meme, scores probablement optimistes.
  Utilise `judgeProvider` pour un juge different.
- **Fallback** : un cas servi par un autre fournisseur ne mesure pas le candidat — il est exclu.
- **Reponses coupees** : `TRUNCATED` penaliserait le format a tort, augmente `maxTokens`.

### Rapports

Un fichier JSON par execution dans `eval-reports/`, avec l'empreinte du dataset, les reglages
de l'execution et le **cout par reponse juste** (cout total / correctness PASS). Un rapport seul
ne dit rien : c'est la **comparaison de deux rapports** (avant / apres un changement de prompt,
de modele, de fournisseur) qui en a. Ecran dedie : portail, onglet Evaluations.

## Resilience

Retry sur panne passagere (429, 5xx, connexion), circuit breaker par fournisseur, fallback dans
l'ordre `gateway.fallback-order`. Regles completes : [`docs/fallback-strategy.md`](docs/fallback-strategy.md).
Etat des circuits : `GET /providers/status`, champ `circuits`.

## Test de non-regression

```bash
scripts/eval-gate.sh ollama --promote   # premiere fois : cree la reference (eval-baselines/)
scripts/eval-gate.sh ollama             # ensuite : echoue si l'etat est REGRESSION
```

Regles seules et temperature 0 : gratuit et reproductible. La reference est versionnee dans
git ; `--promote` ne la remplace que si la nouvelle execution ne regresse pas.

## Ce qu'il te reste a faire

Voir `HANDOFF.md`, sections 7 (plan) et 8 (dettes).

### Ce que tu ne dois PAS ajouter ici

Un endpoint metier, du stockage de conversation, l'execution d'outils, la logique
RAG. Le jour ou tu ajoutes l'un des quatre, le Gateway a commence a mourir.

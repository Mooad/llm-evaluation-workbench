# LLM Portal

Console de test du [LLM Gateway](../llm-gateway) : chat en streaming, mesures par requete,
evaluations et regressions, reglages et tarifs. Angular 22 (standalone, zoneless, signals), SCSS, aucune librairie d'UI.

## Demarrer

Il faut **Node 24** (Angular 22). Il est installe a part, sans toucher au Node 18 global :

```bash
export PATH="$HOME/.local/share/node/current/bin:$PATH"
npm start                 # http://localhost:4200, Gateway attendu sur :8081
npm test                  # vitest
npm run build
```

Le serveur de dev relaie `/api/*` vers le Gateway et `/rag-api/*` vers [llm-rag](../llm-rag)
(`proxy.conf.mjs`) : meme origine pour le navigateur, aucun CORS a ouvrir. Autres instances :
`GATEWAY_URL=http://localhost:8091 RAG_URL=http://localhost:8092 npm start`.
(`/rag-api` et non `/rag` : `/rag` est la route de l'ecran RAG Chat.)

## Ecrans

| Ecran | Role |
| --- | --- |
| **Playground** | chat en streaming (`POST /chat/stream`), bouton Arreter, historique complet renvoye a chaque message (Gateway sans memoire) |
| **RAG Chat** | importer un PDF dans llm-rag et suivre son indexation, choisir les documents interroges, poser des questions : recherche (`POST /search`), puis reponse en streaming du Gateway a partir des extraits, avec citations `[n]` et sources (score, page, extrait). Mode RAG desactivable pour comparer avec le LLM seul |
| **Evaluation RAG** | lancer l'evaluation du RAG sur le jeu de questions de llm-rag, pour un index (strategie de decoupage), un modele qui repond et un modele juge au choix : recherche (bonne page en tete, dans le top K, variantes, bon document), puis reponses avec et sans RAG notees par regle (faits attendus) et par un LLM juge (fidelite, abstention) ; resume en clair, comparaison a une execution precedente, detail par question, historique (rapports stockes par llm-rag) |
| **Evaluations** | lancer une evaluation (dataset, candidat, juge, portee), suivre sa progression, lire un rapport cas par cas, comparer deux executions (etat de regression, cout par reponse juste, latence) |
| **Configuration** | reglages globaux du Gateway (lecture et ecriture), tarifs par modele et simulateur de cout |

## Reglages : trois niveaux

| Niveau | Ou | Portee |
| --- | --- | --- |
| Session | `sessionStorage` du portail | toutes les conversations de l'onglet, conserve au rechargement |
| Global | `settings/defaults.json` du Gateway | tous les clients du Gateway |
| Fournisseur | le fournisseur lui-meme | quand rien n'est impose |

Un champ de session vide n'est pas envoye : le Gateway applique le global. Chaque reponse
affiche les reglages reellement appliques et leur origine (`session`, `global`, `fournisseur`).

## Mesures affichees, et comment elles sont obtenues

| Mesure | Source |
| --- | --- |
| Premier fragment — Gateway | Gateway : reception de la requete -> premier fragment envoye |
| Premier fragment — navigateur | portail : envoi -> premier fragment affiche (reseau et proxy compris) |
| Duree de l'appel | Gateway : appel au fournisseur qui a repondu |
| Tokens | compteur du fournisseur (non comparables d'un fournisseur a l'autre) |
| Cout | Gateway : tokens x tarif de `application.yml`, calcul affiche ; `inconnu` si pas de tarif |

## RAG Chat : qui fait quoi

```
question --POST /rag-api/search--> llm-rag      (embedding de la question, top-K chunks)
         --POST /api/chat/stream--> Gateway     (generation, sans aucune logique RAG)
```

- Le prompt est assemble par le portail (`core/rag-prompt.ts`) : consignes dans le message
  systeme, extraits numerotes et question dans le message utilisateur. Les extraits ne vont
  jamais dans le message systeme : un document ne doit pas avoir l'autorite d'une consigne.
- L'historique renvoye garde questions et reponses, sans les extraits des tours precedents :
  chaque tour fait sa propre recherche.
- Index : A (fenetres de tokens) ou B (par section), les memes documents decoupes autrement ;
  chaque document affiche son etat et son nombre de chunks dans chaque index. « Reconstruire »
  reindexe tous les documents dans l'index choisi, a partir des fichiers conserves par llm-rag.
- Suppression d'un document (icone corbeille, confirmee) : retire de tous les index, avec son fichier.
- Fournisseur et modele : choisis dans Reglages rapides, partages avec le Playground.
- Collection active : la recherche ne porte que sur les documents de cette collection (filtre
  applique par llm-rag). Chaque extrait arrive au modele avec sa section (« p. 224 — Systeme de
  surveillance de pression »), pour qu'il distingue deux variantes d'une meme procedure.
- Mesures par tour : duree de la recherche (navigateur), tokens entree/sortie, latence et cout
  (Gateway). Avec le mode RAG desactive, on voit la meme question sans document.
- Pour comparer RAG et LLM seul proprement, repartir d'une **nouvelle conversation** : sinon
  le LLM seul relit dans l'historique la reponse sourcee du tour precedent.

## Evaluation RAG : comment c'est mesure

```
1. POST /rag-api/evaluations/retrieval     llm-rag cherche pour chaque question (sans LLM)
2. pour chaque question :
     POST /api/chat             reponse avec les extraits (temperature 0)
     POST /api/chat/structured  LLM juge : fidele ? s'abstient ? (JSON valide par le Gateway)
     POST /api/chat             meme question, sans document
3. POST /rag-api/evaluations/reports       rapport complet, stocke par llm-rag
```

- Les faits attendus sont verifies par regle (`core/rag-eval.ts`), sans LLM : minuscules, sans
  accents ni espaces insecables ; `a|b` accepte l'une des formes.
- Le juge ne note pas l'exactitude (deja mesuree), mais ce que les regles ne voient pas : une
  information inventee, ou une reponse qui renonce. Il se choisit a part du modele qui repond :
  un modele qui note ses propres reponses se contredit parfois (mesure sur qwen3-coder, cas
  « capitale de l'Australie »).
- Comparer deux strategies de decoupage : lancer une fois avec l'index A, une fois avec l'index B
  (meme modele, meme juge), puis « Comparer a ».
- Le portail orchestre parce qu'il est le seul a parler aux deux services : llm-rag ne genere
  rien, le Gateway ne connait pas le RAG.
- Duree en local (qwen3-coder:30b) : environ 50 s par question, le modele seul ecrivant des
  reponses longues ; une recherche seule prend environ une minute pour les 35 questions.

## Structure

```
src/app/
  core/           contrats du Gateway et de llm-rag, clients HTTP + SSE, reglages de session,
                  config Gateway, prompt RAG, documents RAG (import, suivi)
  playground/     chat en streaming et mesures
  rag-chat/       ecran RAG Chat : sources, collections, conversation RAG, extraits retrouves
  rag-eval/       ecran Evaluation RAG : lancement, resume, detail par question, historique
  evaluations/    lancement, historique, rapport, regression
  configuration/  reglages globaux et tarifs
  status/         etat des fournisseurs (en-tete)
```

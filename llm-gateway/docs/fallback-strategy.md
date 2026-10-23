# Strategie de fallback fournisseur

Decision : 2026-10-14. Ce document fixe **quand** le Gateway insiste, **quand** il change de
fournisseur, et **quand** il renvoie l'erreur au client. Code : `FailureClassifier`,
`ProviderGuard`, `ChatService`, `FallbackManager`.

## 1. Principe

Une requete doit aboutir si un fournisseur peut repondre, **sans** masquer les erreurs du client
et **sans** depenser d'argent pour rien. Trois mecanismes, dans cet ordre, pour chaque fournisseur :

```
appel ──► echec passager ? ──oui──► retry (meme fournisseur, attente croissante)
             │                         │ toujours en echec
             non (definitif)           ▼
             │                    circuit breaker : compte la panne
             ▼                         │
        fournisseur suivant ◄──────────┘   (fallback, dans l'ordre configure)
```

## 2. Classer l'erreur d'abord

| Erreur | Classe | Retry | Circuit | Fallback | Pourquoi |
| --- | --- | --- | --- | --- | --- |
| 429, 500, 502, 503, 504 | passagere | oui | compte | oui | le fournisseur est surcharge ou en incident : reessayer peut suffire |
| Connexion refusee / coupee | passagere | oui | compte | oui | redemarrage, coupure reseau breve |
| Timeout de lecture | definitive | **non** | non | oui | l'appel a deja pris jusqu'a 180 s ; reessayer doublerait l'attente |
| 400, 404, 422 (requete, modele) | definitive | non | non | oui | le fournisseur repond : il est sain, la requete ne lui convient pas |
| 401, 403 (cle) | definitive | non | non | oui | reessayer ne changera pas la cle |
| Cle absente | definitive | non | non | oui | echec immediat, sans appel reseau |
| Fournisseur inconnu, requete invalide | **erreur client** | non | non | **non** | 400 au client : basculer cacherait son erreur |
| Schema non gere | **erreur client** | non | non | **non** | 400 avant tout appel, aucun token consomme |

Le statut HTTP d'un flux refuse est porte par `ProviderCallException.httpStatus` ; pour un
appel classique il vient de la cause (`RestClientResponseException`).

## 3. Retry

- `gateway.resilience.max-attempts: 2` : un seul nouvel essai. Au-dela, le fallback est plus
  utile que l'insistance.
- Attente `backoff-ms: 300`, doublee a chaque essai (300, 600, 1200 ms).
- **Jamais une fois un flux commence** : reessayer renverrait au client le debut de la reponse
  une seconde fois.
- Chaque retry est logue sur `gateway.resilience` (`provider=... retry=2/2 attente_ms=...`).

## 4. Circuit breaker

- Apres `failure-threshold: 3` pannes **passageres** consecutives, le fournisseur est **ecarte**
  pendant `open-seconds: 30` : rejet immediat, le fallback enchaine sans payer un timeout.
- Ensuite un seul appel d'essai passe (MI-OUVERT) : succes = circuit referme, echec = rouvert.
- Une erreur definitive (4xx) **referme** le compteur : le fournisseur a repondu, il est vivant.
- Etat visible dans `GET /providers/status`, champ `circuits`.

## 5. Ordre de repli

`gateway.fallback-order: [ollama, deepseek, openai]` — gratuit d'abord, puis du moins cher au
plus cher. Explicite : l'ordre de la map `providers` n'est pas fiable (les cles de
`application-secrets.yml` passent devant).

- Le primaire est le fournisseur demande, sinon `default-provider`.
- Chaque repli utilise **son** modele par defaut et **ses** reglages globaux, jamais ceux du primaire.
- Toute la chaine echoue : `502 all_providers_failed`, avec la liste des fournisseurs essayes.

## 6. Cas particuliers

| Contexte | Regle |
| --- | --- |
| Streaming | fallback et retry seulement **avant** le premier fragment ; apres, `error stream_interrupted` |
| Evaluation | `allowFallback: false` par defaut : un cas servi par un repli est **exclu** de la mesure |
| Juge d'evaluation | meme chaine ; la methode du verdict (`juge:<fournisseur>/<modele>`) dit qui a vraiment juge |

## 7. Couts

Basculer d'Ollama vers un fournisseur distant transforme un appel gratuit en appel payant.
Chaque tentative est loguee avec le meme `request_id` : le cout d'une requete, repli compris, se
retrouve avec `grep request_id=<id> logs/gateway.log`.

## 8. Limites connues

- **`allowFallback: false` n'empeche pas l'appel de repli** (dette n°10) : en evaluation, le cas
  est exclu, mais le fournisseur de secours a deja ete appele et facture.
- L'etat des circuits est en memoire : un redemarrage les referme tous.
- Un circuit par fournisseur, pas par modele : un modele en panne chez OpenAI ecarte tout OpenAI.

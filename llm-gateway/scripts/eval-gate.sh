#!/usr/bin/env bash
# Test de non-regression sur le golden dataset.
#
# Rejoue le dataset sur un fournisseur (regles seules, temperature 0), compare a la reference
# versionnee dans eval-baselines/ et sort en echec si l'etat est REGRESSION.
#
#   scripts/eval-gate.sh                  # ollama, compare a la reference
#   scripts/eval-gate.sh deepseek         # autre fournisseur
#   scripts/eval-gate.sh ollama --promote # cree la reference, ou la remplace si pas de regression
#
# Code de sortie : 0 = pas de regression ; non nul = regression OU pas encore de reference
# (Maven ramene tout code non nul a 1 : le message affiche dit lequel).
set -euo pipefail
cd "$(dirname "$0")/.."

provider="${1:-ollama}"
promote=false
[[ "${2:-}" == "--promote" ]] && promote=true

# web-application-type=none : pas de serveur HTTP, donc aucun conflit avec un Gateway deja lance.
exec mvn -o -q spring-boot:run -Dspring-boot.run.arguments="\
--spring.main.web-application-type=none --spring.main.banner-mode=off \
--logging.level.root=WARN --logging.level.gateway.calls=WARN \
--gateway.eval.gate.enabled=true \
--gateway.eval.gate.provider=${provider} \
--gateway.eval.gate.promote=${promote}"

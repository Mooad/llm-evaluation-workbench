# Scripts

## Corpus de test (`docs_rag/10` a `19`)

Documents **fictifs** generes depuis `test-corpus/*.md` : chaque `## Section` commence une page
et devient un signet du sommaire PDF. Modifier un `.md` change les pages : mettre a jour
`src/main/resources/evaluation/rag-dataset.json` en consequence (le generateur affiche la
premiere page de chaque section).
Les sources Markdown du corpus restent locales et sont ignorees par Git ; les fournir
avant d'executer le generateur sur une nouvelle copie du depot.

```bash
cd llm-rag
CP=$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)
javac -d target/corpus -cp "$CP" scripts/GenerateTestCorpus.java
java -cp "$CP:target/corpus" GenerateTestCorpus scripts/test-corpus docs_rag
```

Regenerer produit des fichiers differents octet par octet (date de creation du PDF) : leur
empreinte, donc leur `documentId`, change. Reimporter ensuite les documents dans llm-rag.

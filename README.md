# LLM Evaluation Workbench

Three sibling applications for local LLM experiments and evaluation:

| Project | Purpose | Development port |
| --- | --- | --- |
| [llm-gateway](llm-gateway/README.md) | Model access, settings, streaming, and evaluation | 8081 |
| [llm-rag](llm-rag/README.md) | PDF ingestion, indexing, search, and retrieval evaluation | 8082 |
| [llm-portal](llm-portal/README.md) | Angular interface for the Gateway and RAG service | 4200 |

Run each application from its own directory. The portal's development proxy connects
to the Gateway and RAG service on their default ports. See the linked READMEs for
prerequisites and commands.

## Local configuration

To use remote providers, create the Gateway's local secrets file from the tracked
structure template:

```bash
cd llm-gateway
cp -n application-secrets.example.yml application-secrets.yml
```

Fill in the `api-key` values for the providers you use. The real
`application-secrets.yml` is ignored by Git; the example contains no credentials.
You can also use the `DEEPSEEK_API_KEY` and `OPENAI_API_KEY` environment variables
without creating a secrets file. See the Gateway README for details.

## Repository

This folder is the unified Git repository. The original local Git metadata for
`llm-gateway` and `llm-rag` is retained in ignored `.local-repo-history/` for
recovery. It is not part of commits from this repository.

# Knowledge/RAG enhancement

This module follows a lightweight, embeddable adaptation of the useful parts of
RAGFlow's document and retrieval pipeline. It does not embed RAGFlow's Python OCR/CV
runtime in a Spring application.

## Implemented baseline

- `STRUCTURE_AWARE` chunking tracks Markdown heading paths, protects fenced code and
  tables, labels block types, and recursively splits only oversized semantic blocks.
- Retrieval is vector-only. The module deliberately does not build persisted keyword
  tokens or scan all chunks in the JVM for sparse recall. Applications that need BM25
  use `agent-start-store-elasticsearch`, whose BM25 and dense channels share one index.
- `agent-start-rag-api` owns the stable policy/result models and query/retrieval/fusion/
  rerank/context SPIs. `agent-start-rag-query`, `agent-start-rag-rerank`, and
  `agent-start-rag-agent` are optional implementations rather than dependencies of the
  storage-neutral knowledge core.
- Query planning supports direct, multi-query, HyDE, step-back and decomposition modes;
  multiple query/channel result lists can be fused with RRF. The agent module publishes
  a `knowledge_search` tool so an agent can decide whether to retrieve and retry after
  inspecting weak evidence.
- The recall pool is configurable with `recallMultiplier`; optional reranking is
  applied to the expanded pool. `maxChunksPerDocument` can improve source diversity.
- Existing chunkers, vector stores and reranker SPIs remain replaceable Spring beans.
- Golden retrieval datasets are evaluated with Recall@K, Precision@K, MRR, nDCG,
  no-answer rejection rate and citation accuracy. `RetrievalRegressionGate` compares
  a candidate report with the accepted baseline using per-metric drop tolerances.
- Index generations persist embedding/chunking/content versions and move through
  `REBUILDING → ACTIVE → RETIRED` (or `FAILED`). Activation locks the dataset row and
  atomically swaps the active pointer, so a failed rebuild never hides the old index.
- Async ingestion has a database idempotency key, expiring claim lease, exponential
  retry schedule, poison isolation and operator replay. Index writes are retry-safe;
  partial segment/vector writes are compensated before the next attempt.

## Configuration example

```json
{
  "processRule": {
    "template": "STRUCTURE_AWARE",
    "chunkTokens": 384,
    "overlapTokens": 48,
    "protectStructuredBlocks": true,
    "includeHeadingContext": true
  },
  "retrievalConfig": {
    "method": "VECTOR",
    "topK": 8,
    "recallMultiplier": 6,
    "maxChunksPerDocument": 3,
    "rerankEnabled": true,
    "rerankPoolSize": 40,
    "ragPolicy": {
      "queryPlanning": { "strategy": "MULTI_QUERY", "maxQueries": 4, "includeOriginal": true },
      "retrieval": { "channels": ["VECTOR", "SPARSE"], "candidateLimitPerQuery": 50, "parallelism": 4 },
      "fusion": { "method": "RRF", "rrfK": 60 },
      "rerank": { "enabled": true, "provider": "score", "candidateLimit": 30, "topN": 8, "failOpen": true },
      "context": { "parentExpansion": true, "neighborWindow": 1, "deduplicate": true, "compression": false, "maxTokens": 6000 },
      "budget": { "maxQueries": 4, "maxRetrievalRounds": 1, "maxLlmCalls": 2, "maxCandidates": 100, "maxEvidenceItems": 12 }
    }
  }
}
```

## Next stages

1. Add format-specific structured readers for DOCX/PDF tables and page/heading metadata.
2. Add synonym dictionaries as an optional query-planner implementation.
3. Persist evaluation datasets/reports and expose experiment comparison in the admin UI.
4. Add a remote OCR/layout-parser adapter and LLM contextual-compression implementation.
5. Add optional GraphRAG/RAPTOR indexing as separate starters after the retrieval baseline
   has measurable evaluation coverage.

## Regression gate example

```java
var report = evaluator.evaluate(goldenSet, "chunk-384/embed-v2/rerank-v1", 10,
        Map.of("chunking", "chunk-384", "embedding", "embed-v2", "reranker", "rerank-v1"),
        query -> retriever.apply(query).stream().map(RetrievedSegment::getSegmentId).toList());

var gate = regressionGate.compare(acceptedBaseline, report,
        new RetrievalRegressionGate.Thresholds(.02, .02, .02, .02, .01, .01));
if (!gate.passed()) throw new IllegalStateException(gate.violations().toString());
```

For an index rebuild, call `beginRebuild(...)`, write every chunk with
`IndexingService.index(..., versionId)`, run the regression gate, then call
`activate(...)`. Calling `activate(...)` on the previous `RETIRED` generation is the
rollback operation. Failed jobs remain in the sidecar table as `POISONED`; after the
source/configuration is fixed, `IngestionRecoveryService.replay(...)` resets and
requeues the job explicitly.

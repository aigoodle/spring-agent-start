# Knowledge/RAG enhancement

This module follows a lightweight, embeddable adaptation of the useful parts of
RAGFlow's document and retrieval pipeline. It does not embed RAGFlow's Python OCR/CV
runtime in a Spring application.

## Implemented baseline

- `STRUCTURE_AWARE` chunking tracks Markdown heading paths, protects fenced code and
  tables, labels block types, and recursively splits only oversized semantic blocks.
- Keyword recall uses CJK bigrams plus IDF-like rare-term weighting. Matches in the
  document title and heading receive higher weights than ordinary body matches.
- Hybrid recall supports `RECIPROCAL_RANK` (the default) and `WEIGHTED_SCORE` fusion.
  RRF is recommended when vector-store and sparse-search scores are not calibrated.
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
    "method": "HYBRID",
    "topK": 8,
    "fusionMethod": "RECIPROCAL_RANK",
    "vectorWeight": 0.7,
    "rrfK": 60,
    "recallMultiplier": 6,
    "maxChunksPerDocument": 3,
    "rerankEnabled": true,
    "rerankPoolSize": 40
  }
}
```

## Next stages

1. Add format-specific structured readers for DOCX/PDF tables and page/heading metadata.
2. Add optional query expansion and synonym dictionaries through a new query-transform SPI.
3. Persist evaluation datasets/reports and expose experiment comparison in the admin UI.
4. Add optional adjacent-chunk context expansion and a remote OCR/layout-parser adapter.
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

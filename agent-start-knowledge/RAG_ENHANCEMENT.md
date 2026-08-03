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
3. Add retrieval evaluation datasets with Recall@K, MRR and nDCG regression reports.
4. Add optional adjacent-chunk context expansion and a remote OCR/layout-parser adapter.
5. Add optional GraphRAG/RAPTOR indexing as separate starters after the retrieval baseline
   has measurable evaluation coverage.

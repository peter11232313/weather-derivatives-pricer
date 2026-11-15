# 0001 — Parquet reader for the Java pricer

- Status: accepted
- Date: 2026-11-16
- Spec: [001 — HDD index](../specs/001-hdd-index.md)

## Context
`data-prep` writes one Parquet file per city (`date`, `tmax_c`, `tmin_c`). The pricer
must read it on Windows (developer machine) and Linux (CI) with as few dependencies as
possible (AGENTS.md: every new dependency needs a reason).

## Decision
Use **Hardwood** (`dev.hardwood:hardwood-core:1.0.1.Final`) behind
`ParquetTemperatureLoader`, with **snappy-java** at runtime only. Test fixtures are
written with **DuckDB JDBC** in test scope.

## Alternatives
- **parquet-java (parquet-mr)**: the reference implementation, but it pulls in Hadoop
  and Avro, and Hadoop needs `winutils.exe`/`HADOOP_HOME` on Windows.
- **DuckDB as the runtime reader**: one artifact, but a ~77 MB jar (bundled native libraries) for
  reading three columns; kept to test scope instead.
- **Hardwood 1.1.0 betas**: add a writer, but not yet final.

## Consequences
- Zero transitive dependencies for the reader; Snappy, gzip and uncompressed files are
  supported. zstd, LZ4 and Brotli are not (each needs another optional library), so
  `data-prep` must write Snappy explicitly. Unsupported files fail with a
  `TemperatureDataException` naming the path.
- Hardwood is young (1.0 in July 2026). Only `ParquetTemperatureLoader` imports it, so a
  swap touches one class; the loader tests (DuckDB-written fixtures) are the safety net.
- snappy-java and DuckDB ship native libraries; verified on Windows locally, CI checks
  Linux.

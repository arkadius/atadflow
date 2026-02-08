# State: Atadflow

## Current Position

Phase: Phase 1 — Spark Connect Infrastructure
Plan: Not yet planned
Status: Ready to plan
Last activity: 2026-02-08 — Roadmap created (2 phases, 8 requirements)

## Project Reference

See: .planning/PROJECT.md (updated 2026-02-08)

**Core value:** Users can visually design a streaming pipeline and execute it on Spark without writing code.
**Current focus:** Milestone v1.0 — Minimal Spark Execution

## Accumulated Context

- Brownfield project — backend + frontend fully functional for flow design
- SparkSubmissionService is a stub — the key gap this milestone closes
- 12 backend tests all passing
- CodeGenerationService produces valid PySpark from flow DAG
- Architectural decision: Python subprocess + Spark Connect (not spark-submit, not REST API)
- Python+PySpark installed on host (Docker image with Python deferred to next milestone)

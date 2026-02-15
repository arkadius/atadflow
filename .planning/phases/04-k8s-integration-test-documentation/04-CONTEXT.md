# Phase 4: K8s Integration Test & Documentation - Context

**Gathered:** 2026-02-15
**Status:** Ready for planning

<domain>
## Phase Boundary

Prove the Helm chart works on a real Kubernetes cluster with full flow submission to Spark Connect, and document the Telepresence local development workflow. Covers HELM-05 (K8s integration test) and HELM-06 (README Telepresence section).

</domain>

<decisions>
## Implementation Decisions

### Test environment assumptions
- k3d is already running and .kube/config points to it
- Helm CLI is installed
- Docker image is pre-built (documented prerequisite, not automated in test)
- Image loaded into k3d via `k3d image import` as a documented prerequisite step
- No Testcontainers K3s — use the real external k3d cluster

### Spark Connect setup
- Use Apache Spark K8s Operator (from Phase 2 research) to deploy Spark Connect
- Operator deployed as part of test setup (Helm install)

### Test format & scope
- JUnit test class (like DockerComposeIntegrationTest), not a shell script
- Full application lifecycle: create flow via API, submit to Spark, poll until SUCCEEDED, verify output
- Proves entire stack end-to-end: Helm chart deploys, app starts, connects to PostgreSQL, submits to Spark Connect, job completes

### Test gating
- Gated by env var (same pattern as DockerComposeIntegrationTest)

### Telepresence documentation
- Quick-start only: prerequisites + 5-step workflow (connect, intercept, quarkusDev, iterate, leave)
- No troubleshooting section, VPN notes, or frontend workflow

### Claude's Discretion
- Specific Helm test hook design (keep/replace test-connection.yaml)
- Test timeouts and polling intervals
- Cleanup strategy (helm uninstall in @AfterAll or leave for debugging)
- Exact REST-Assured assertion patterns
- Which flow template to use for the submission test (rate source + console is simplest)

</decisions>

<specifics>
## Specific Ideas

- Follow DockerComposeIntegrationTest patterns for consistency (same package, same REST-Assured style)
- Use ProcessBuilder or Runtime.exec for helm/kubectl commands from JUnit
- The existing rate source + console flow is the simplest proof of Spark execution

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 04-k8s-integration-test-documentation*
*Context gathered: 2026-02-15*

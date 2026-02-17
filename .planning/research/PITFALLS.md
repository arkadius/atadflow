# Domain Pitfalls: Helm Chart Kubernetes Deployment

**Domain:** Adding Helm chart Kubernetes deployment to subprocess-executing Java/Quarkus app (Atadflow)
**Researched:** 2026-02-15
**Confidence:** HIGH

## Critical Pitfalls

### Pitfall 1: Subprocess OOMKilled - Container Limit Kills Parent and Children

**What goes wrong:**
PySpark subprocess spawned by Java ProcessBuilder exceeds pod memory limit, triggering OOMKilled. Kubernetes kills the ENTIRE pod (Java + Python subprocess together), not just the subprocess. Job status stuck at RUNNING in database because Java process terminated before updating status. Users see incomplete job state.

**Why it happens:**
Kubernetes enforces memory limits at the cgroup (container) level, not per-process. When Java allocates 512MB heap and Python subprocess allocates 800MB for Spark data processing, total RSS exceeds pod limit (1Gi). Linux OOM killer terminates the pod. Current Atadflow architecture (SparkSubmissionService.java line 35-38) spawns subprocess with `ProcessBuilder` but doesn't reserve memory headroom for subprocesses in container limits.

**Consequences:**
- Pod terminated with exit code 137 (OOMKilled)
- Job database record shows RUNNING but pod is dead
- No error message captured (Java killed before handleProcessExit callback)
- Kubernetes restarts pod, new instance doesn't know about orphaned job
- User has no visibility into why job disappeared

**Prevention:**
1. **Set JVM max heap to 60-70% of pod memory limit** - Reserve headroom for subprocesses
   ```yaml
   # values.yaml
   resources:
     limits:
       memory: "2Gi"  # Container limit
     requests:
       memory: "1.5Gi"
   env:
     - name: JAVA_OPTS
       value: "-Xmx1400m"  # JVM heap = 70% of 2Gi
   ```

2. **Configure subprocess memory limits via environment variables** - Pass to Python
   ```java
   // SparkSubmissionService.java
   pb.environment().put("PYSPARK_DRIVER_MEMORY", "512m");
   pb.environment().put("PYSPARK_EXECUTOR_MEMORY", "512m");
   ```

3. **Add preStop hook to update job status before termination**
   ```yaml
   # Helm chart
   lifecycle:
     preStop:
       exec:
         command: ["/bin/sh", "-c", "curl -X POST http://localhost:8080/internal/shutdown"]
   ```

4. **Monitor container memory metrics** - Alert before OOM
   ```yaml
   # Prometheus rule
   alert: PodMemoryHigh
   expr: container_memory_working_set_bytes / container_spec_memory_limit_bytes > 0.9
   ```

**Detection:**
- Pod status shows `OOMKilled` in `kubectl describe pod`
- `container_memory_working_set_bytes` metric approaches limit
- Job status "RUNNING" but pod not found
- Backend logs show no ERROR, just disappear mid-execution

**Phase to address:**
Phase 1 (Helm Chart Basics) - Set resource limits with subprocess headroom. Phase 2 (Production Hardening) - Add preStop hook and memory monitoring.

**Source confidence:** HIGH - Verified with Kubernetes memory management docs and OOM killer behavior.

---

### Pitfall 2: gRPC Spark Connect DNS Resolution Failure - Hardcoded Localhost Breaks

**What goes wrong:**
Quarkus backend connects to Spark Connect using `sc://localhost:15002` (hardcoded in application.properties line 25). In Kubernetes, "localhost" resolves to pod's loopback, not the Spark Connect service. Jobs fail immediately with "gRPC UNAVAILABLE: Connection refused". Error message unhelpful: "Failed to start process: No route to host".

**Why it happens:**
Docker Compose networking uses service names (`spark-connect`) on shared network. Kubernetes DNS resolves service names to ClusterIP. Current config uses `%prod.spark.connect.url=${SPARK_CONNECT_URL:sc://spark-connect:15002}` which works in Docker Compose but breaks if Helm chart uses different service name or external Spark cluster. gRPC load balancing issues compound problem - Kubernetes Service uses connection-level LB, but HTTP/2 connection reuse means all requests go to single pod.

**Consequences:**
- All job submissions fail with connection errors
- Health check PythonLivenessCheck passes (checks Python binary, not Spark connectivity)
- Liveness probe shows UP, pod keeps running, but functionality broken
- Users see "Spark server unreachable" with no actionable guidance
- If Spark Connect has multiple replicas, only one receives traffic (connection pinning)

**Prevention:**
1. **Use Kubernetes Service DNS with explicit namespace**
   ```properties
   # application.properties
   %prod.spark.connect.url=${SPARK_CONNECT_URL:sc://spark-connect.default.svc.cluster.local:15002}
   ```

2. **Make Spark Connect URL templated in Helm values**
   ```yaml
   # values.yaml
   sparkConnect:
     url: "sc://spark-connect:15002"  # Internal service
     # OR
     url: "sc://external-spark.example.com:443"  # External cluster

   # deployment.yaml
   env:
     - name: SPARK_CONNECT_URL
       value: {{ .Values.sparkConnect.url }}
   ```

3. **Add readiness probe for Spark Connect connectivity** - Fail fast
   ```yaml
   readinessProbe:
     exec:
       command:
         - /bin/sh
         - -c
         - python3 -c "from pyspark.sql import SparkSession; SparkSession.builder.remote('$SPARK_CONNECT_URL').getOrCreate().sql('SELECT 1').show()"
     initialDelaySeconds: 30
     periodSeconds: 30
   ```

4. **Configure gRPC DNS resolver for client-side load balancing** (if multiple Spark Connect replicas)
   ```properties
   # Use dns:/// scheme for gRPC DNS-based load balancing
   spark.connect.url=sc://dns:///spark-connect:15002
   ```

5. **Use headless service for Spark Connect** - Enable client-side LB
   ```yaml
   # spark-connect-service.yaml
   apiVersion: v1
   kind: Service
   metadata:
     name: spark-connect
   spec:
     clusterIP: None  # Headless - returns all pod IPs
     selector:
       app: spark-connect
     ports:
       - port: 15002
   ```

**Detection:**
- Job submission fails with "Connection refused" or "No route to host"
- `kubectl logs` shows: "io.grpc.StatusRuntimeException: UNAVAILABLE"
- `kubectl exec` into pod → `nc -zv spark-connect 15002` fails
- DNS resolution: `nslookup spark-connect` returns no records

**Phase to address:**
Phase 1 (Helm Chart Basics) - Template Spark Connect URL, add DNS FQDN. Phase 3 (Scalability) - Add headless service and client-side LB.

**Source confidence:** HIGH - gRPC Kubernetes load balancing is well-documented pitfall.

---

### Pitfall 3: PostgreSQL Subchart Data Loss - PVC Not Created, Restart Wipes DB

**What goes wrong:**
Helm chart includes PostgreSQL subchart with default settings. First deployment works. Upgrade or pod restart causes PostgreSQL to start fresh - all flows, jobs, and history lost. Users report "My flows disappeared". Root cause: persistence disabled by default or PVC not bound.

**Why it happens:**
Bitnami PostgreSQL chart defaults to ephemeral storage if `persistence.enabled=false`. Even if enabled, PVC may not bind if StorageClass doesn't exist (k3d uses `local-path` by default, some clusters have no default). Password randomization on upgrade creates new secret, pod starts but can't access old PVC data with new password. Init scripts (Flyway migrations) run on clean DB, not detecting existing schema.

**Consequences:**
- Complete data loss on pod restart
- Flyway migration conflicts if PVC reconnects (schema exists but version table empty)
- Users lose work, trust in application destroyed
- No warning during initial deployment (works until first restart)
- Backup/restore process doesn't exist

**Prevention:**
1. **Enable persistence with explicit StorageClass**
   ```yaml
   # values.yaml
   postgresql:
     enabled: true
     auth:
       username: atadflow
       password: atadflow  # NEVER use default random password
       database: atadflow
     primary:
       persistence:
         enabled: true
         storageClass: "local-path"  # k3d default, "" for cluster default
         size: 8Gi
         # CRITICAL: Set to Retain so data survives chart uninstall
         existingClaim: ""  # Leave empty for dynamic provisioning
   ```

2. **Pin PostgreSQL subchart version** - Prevent breaking changes
   ```yaml
   # Chart.yaml
   dependencies:
     - name: postgresql
       version: "16.3.5"  # Pin exact version
       repository: https://charts.bitnami.com/bitnami
       condition: postgresql.enabled
   ```

3. **Set PVC retention policy to Retain**
   ```yaml
   # StorageClass should have reclaimPolicy: Retain
   # OR use static PV with Retain policy
   ```

4. **Add backup CronJob to Helm chart**
   ```yaml
   # templates/cronjob-backup.yaml
   apiVersion: batch/v1
   kind: CronJob
   metadata:
     name: postgres-backup
   spec:
     schedule: "0 2 * * *"  # Daily 2am
     jobTemplate:
       spec:
         template:
           spec:
             containers:
               - name: backup
                 image: postgres:16
                 command:
                   - /bin/sh
                   - -c
                   - pg_dump -h postgres -U atadflow atadflow | gzip > /backup/$(date +%Y%m%d).sql.gz
                 volumeMounts:
                   - name: backup
                     mountPath: /backup
             volumes:
               - name: backup
                 persistentVolumeClaim:
                   claimName: postgres-backup-pvc
   ```

5. **Validate PVC binding during installation**
   ```yaml
   # templates/tests/test-pvc.yaml
   apiVersion: v1
   kind: Pod
   metadata:
     name: test-pvc-binding
     annotations:
       "helm.sh/hook": test
   spec:
     containers:
       - name: test
         image: busybox
         command: ['sh', '-c', 'test -d /var/lib/postgresql/data']
         volumeMounts:
           - name: data
             mountPath: /var/lib/postgresql/data
     volumes:
       - name: data
         persistentVolumeClaim:
           claimName: {{ include "atadflow.fullname" . }}-postgresql
   ```

6. **Document credential rotation procedure** - Change password without data loss
   ```bash
   # 1. Connect to running pod
   kubectl exec -it postgres-0 -- psql -U atadflow
   # 2. Change password
   ALTER USER atadflow PASSWORD 'new-password';
   # 3. Update Helm values
   # 4. Upgrade chart (don't delete PVC)
   ```

**Detection:**
- `kubectl get pvc` shows no PVC or STATUS=Pending
- `kubectl describe pvc` shows "Waiting for first consumer to be created"
- PostgreSQL logs: "database system was shut down at..." (data directory empty)
- Flyway logs: "Successfully applied 5 migrations" (should see "Schema history already exists")

**Phase to address:**
Phase 1 (Helm Chart Basics) - Enable persistence, pin subchart version, set explicit StorageClass. Phase 2 (Production Hardening) - Add backup CronJob and retention policy.

**Source confidence:** HIGH - Helm PostgreSQL subchart data loss is common pitfall, documented extensively.

---

### Pitfall 4: Temp File Cleanup Failure - /tmp Read-Only in Security-Hardened Pods

**What goes wrong:**
SparkSubmissionService creates temp Python files with `Files.createTempFile("spark-job-", ".py")` (line 32). In security-hardened Kubernetes deployment with `readOnlyRootFilesystem: true`, temp file creation fails with "Read-only file system". Jobs never start. Users see cryptic error: "Failed to start process: /tmp (Read-only file system)".

**Why it happens:**
Security best practice: set `securityContext.readOnlyRootFilesystem: true` to prevent container compromise via writable filesystem. Java `Files.createTempFile()` writes to `/tmp` by default. `/tmp` is on root filesystem, now read-only. No emptyDir volume mounted for temp files. Non-root user (UID 1001 in Dockerfile line 60-64) can't write even if filesystem were writable without fsGroup.

**Consequences:**
- All job submissions fail immediately
- Error not visible in UI (caught in catch block, logged as generic failure)
- Health checks pass (don't test temp file creation)
- Workaround: disable `readOnlyRootFilesystem` - reduces security posture
- Temp files accumulate if not cleaned (handleProcessExit cleanup fails silently)

**Prevention:**
1. **Mount emptyDir volume for /tmp** - Writable temp space
   ```yaml
   # values.yaml
   securityContext:
     readOnlyRootFilesystem: true
     runAsNonRoot: true
     runAsUser: 1001
     fsGroup: 1001

   # deployment.yaml
   spec:
     containers:
       - name: atadflow
         volumeMounts:
           - name: tmp
             mountPath: /tmp
           - name: java-tmp
             mountPath: /deployments/tmp  # Java temp dir
     volumes:
       - name: tmp
         emptyDir: {}
       - name: java-tmp
         emptyDir: {}
   ```

2. **Configure Java to use mounted temp directory**
   ```yaml
   env:
     - name: JAVA_OPTS
       value: "-Djava.io.tmpdir=/deployments/tmp -Xmx1400m"
   ```

3. **Set emptyDir size limits** - Prevent unbounded growth
   ```yaml
   volumes:
     - name: tmp
       emptyDir:
         sizeLimit: "1Gi"  # Limit temp file storage
   ```

4. **Add init container to set permissions** (if fsGroup insufficient)
   ```yaml
   initContainers:
     - name: fix-permissions
       image: busybox
       command: ['sh', '-c', 'chmod 1777 /tmp && chmod 1777 /deployments/tmp']
       volumeMounts:
         - name: tmp
           mountPath: /tmp
         - name: java-tmp
           mountPath: /deployments/tmp
   ```

5. **Implement temp file cleanup monitoring**
   ```java
   // Add to SparkSubmissionService
   @Scheduled(every = "1h")
   void cleanupOldTempFiles() {
       try (var files = Files.list(Paths.get(System.getProperty("java.io.tmpdir")))) {
           files.filter(p -> p.getFileName().toString().startsWith("spark-job-"))
                .filter(p -> Files.getLastModifiedTime(p).toInstant()
                    .isBefore(Instant.now().minus(Duration.ofHours(1))))
                .forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (IOException e) { LOG.warn("Failed to clean temp file", e); }
                });
       }
   }
   ```

**Detection:**
- `kubectl logs` shows: "java.io.IOException: Read-only file system"
- `kubectl exec` into pod → `touch /tmp/test` fails
- Job status immediately FAILED with error message "Failed to start process"
- `df -h` shows `/tmp` mounted on root filesystem, not emptyDir

**Phase to address:**
Phase 1 (Helm Chart Basics) - Add emptyDir volumes for /tmp. Phase 2 (Production Hardening) - Add size limits and cleanup monitoring.

**Source confidence:** HIGH - Read-only filesystem with emptyDir is standard Kubernetes security pattern.

---

### Pitfall 5: Health Probe Timing Mismatch - Startup Probe Kills Slow-Starting Pods

**What goes wrong:**
Quarkus app takes 45 seconds to start on first boot (Hibernate schema validation, connection pool init, Python dependency check). Kubernetes liveness probe configured with `initialDelaySeconds: 30` and `failureThreshold: 3`. Probe fails at 30s, 40s, 50s → pod killed before app ready. Restart loop. "CrashLoopBackOff". App never stabilizes.

**Why it happens:**
Current Dockerfile has `HEALTHCHECK --start-period=60s` but Kubernetes ignores Docker HEALTHCHECK. Helm chart doesn't distinguish startup vs liveness probes. Liveness probe starts immediately, fails during slow startup. Quarkus startup time increases in Kubernetes due to: DNS resolution timeouts, PostgreSQL connection establishment, PVC mounting delays. PythonLivenessCheck spawns subprocess (`python --version`) which is slow on first invocation.

**Consequences:**
- Pod never becomes Ready, no traffic routed
- Restart loop delays deployment by 10+ minutes
- Image pull backoff compounds problem
- Users see "Service Unavailable" indefinitely
- Dev environment (fast local startup) doesn't reveal issue

**Prevention:**
1. **Use separate startup, liveness, and readiness probes**
   ```yaml
   # values.yaml
   probes:
     startup:
       enabled: true
       httpGet:
         path: /q/health/started
         port: http
       initialDelaySeconds: 10
       periodSeconds: 5
       failureThreshold: 30  # 10s + (5s * 30) = 160s max startup time
     liveness:
       enabled: true
       httpGet:
         path: /q/health/live
         port: http
       initialDelaySeconds: 0  # Startup probe takes precedence
       periodSeconds: 10
       failureThreshold: 3
       timeoutSeconds: 5
     readiness:
       enabled: true
       httpGet:
         path: /q/health/ready
         port: http
       initialDelaySeconds: 0
       periodSeconds: 5
       failureThreshold: 3
       timeoutSeconds: 3
   ```

2. **Add Quarkus startup health check endpoint**
   ```java
   // StartupHealthCheck.java
   @Startup
   @ApplicationScoped
   public class StartupHealthCheck implements HealthCheck {
       @Inject
       DataSource dataSource;

       @Override
       public HealthCheckResponse call() {
           // Fast check - just verify critical components initialized
           try {
               dataSource.getConnection().close();
               return HealthCheckResponse.up("startup");
           } catch (Exception e) {
               return HealthCheckResponse.down("startup");
           }
       }
   }
   ```

3. **Optimize startup time** - Reduce first-boot delay
   ```properties
   # application.properties
   # Disable dev services in prod
   %prod.quarkus.devservices.enabled=false

   # Use connection pool minimum size to avoid cold start
   %prod.quarkus.datasource.jdbc.min-size=1
   %prod.quarkus.datasource.jdbc.max-size=10

   # Reduce Hibernate schema validation time
   %prod.quarkus.hibernate-orm.database.generation=none
   ```

4. **Configure Quarkus native image** (optional) - Sub-second startup
   ```yaml
   # values.yaml (if using native image)
   image:
     repository: atadflow
     tag: "1.2.0-native"
   probes:
     startup:
       initialDelaySeconds: 5
       failureThreshold: 6  # 5s + (5s * 6) = 35s max startup
   ```

5. **Set terminationGracePeriodSeconds > graceful stop timeout**
   ```yaml
   # deployment.yaml
   spec:
     template:
       spec:
         terminationGracePeriodSeconds: 60  # Must be > Spark graceful stop timeout
   ```

**Detection:**
- Pod status: `CrashLoopBackOff`
- `kubectl describe pod` → Events: "Liveness probe failed: HTTP probe failed with statuscode: 503"
- Logs show app still initializing when killed
- Metrics: `kube_pod_container_status_restarts_total` increasing

**Phase to address:**
Phase 1 (Helm Chart Basics) - Configure startup/liveness/readiness probes correctly. Phase 2 (Production Hardening) - Optimize startup time, consider native image.

**Source confidence:** HIGH - Kubernetes probe configuration and Quarkus startup timing well-documented.

---

### Pitfall 6: Helm Dependency Version Drift - Subchart Update Breaks Deployment

**What goes wrong:**
Initial Helm chart specifies PostgreSQL dependency with version range `version: "~16.0.0"` (allows minor updates). Bitnami releases PostgreSQL chart 16.1.0 with breaking change: renamed value `persistence.existingClaim` → `persistence.claimName`. `helm upgrade` pulls new subchart version, deployment fails with "unknown field: persistence.existingClaim". Rollback fails due to PVC naming conflict.

**Why it happens:**
Helm dependency resolution uses semantic versioning ranges. Bitnami frequently updates charts with breaking changes in minor versions. `helm dependency update` fetches latest matching version, not reproducible. Chart.lock not committed to Git or stale. Different developers/CI runs get different subchart versions. Values.yaml overrides become incompatible with new subchart API.

**Consequences:**
- `helm upgrade` fails mid-deployment, old pods terminating, new pods failing
- Can't rollback cleanly (StatefulSet PVC mismatch)
- Production outage during upgrade window
- Different environments (dev/staging/prod) drift to different subchart versions
- CI/CD pipeline non-deterministic

**Prevention:**
1. **Pin exact subchart versions** - No ranges
   ```yaml
   # Chart.yaml
   dependencies:
     - name: postgresql
       version: "16.3.5"  # EXACT version, no ~ or ^
       repository: https://charts.bitnami.com/bitnami
       condition: postgresql.enabled
   ```

2. **Commit Chart.lock to version control** - Reproducible builds
   ```bash
   helm dependency update
   git add Chart.lock
   git commit -m "Lock PostgreSQL subchart to 16.3.5"
   ```

3. **Test subchart upgrades in staging first**
   ```bash
   # Before upgrading in production
   helm dependency update --skip-refresh  # Use Chart.lock
   helm diff upgrade atadflow ./chart --namespace staging
   helm upgrade --dry-run --debug atadflow ./chart
   ```

4. **Use global values for subchart overrides** - More stable API
   ```yaml
   # values.yaml
   global:
     postgresql:
       auth:
         username: atadflow

   postgresql:
     enabled: true
     global:
       postgresql:
         auth:
           database: atadflow
   ```

5. **Document subchart upgrade procedure**
   ```markdown
   ## Upgrading PostgreSQL Subchart

   1. Review Bitnami changelog: https://github.com/bitnami/charts/tree/main/bitnami/postgresql
   2. Check for breaking changes in values.yaml schema
   3. Update Chart.yaml with new version
   4. Run `helm dependency update`
   5. Validate values.yaml overrides against new schema
   6. Test in dev environment
   7. Update Chart.lock and commit
   ```

6. **Add Helm test for subchart configuration**
   ```yaml
   # templates/tests/test-postgres-connection.yaml
   apiVersion: v1
   kind: Pod
   metadata:
     name: test-postgres-connection
     annotations:
       "helm.sh/hook": test
   spec:
     containers:
       - name: test
         image: postgres:16
         command:
           - psql
           - -h
           - {{ include "atadflow.fullname" . }}-postgresql
           - -U
           - {{ .Values.postgresql.auth.username }}
           - -c
           - SELECT 1
         env:
           - name: PGPASSWORD
             valueFrom:
               secretKeyRef:
                 name: {{ include "atadflow.fullname" . }}-postgresql
                 key: password
   ```

**Detection:**
- `helm upgrade` fails with "unknown field" or schema validation errors
- Different Chart.lock between local and CI/CD
- `helm dependency list` shows version mismatch
- Subchart rendering fails: `helm template --debug`

**Phase to address:**
Phase 1 (Helm Chart Basics) - Pin exact versions, commit Chart.lock. Phase 2 (Production Hardening) - Add upgrade testing and documentation.

**Source confidence:** HIGH - Helm dependency management pitfalls well-documented in Helm best practices.

---

### Pitfall 7: k3d Image Pull Failure - Local Image Not Imported, Pod Stuck ImagePullBackOff

**What goes wrong:**
Developer builds Atadflow Docker image locally: `docker build -t atadflow:1.2.0`. Helm chart specifies `image.repository: atadflow, image.tag: 1.2.0`. Deploy to k3d cluster. Pod status: `ImagePullBackOff`. Error: "Failed to pull image 'atadflow:1.2.0': rpc error: code = Unknown desc = failed to pull and unpack image". Image exists in local Docker but k3d can't see it.

**Why it happens:**
k3d runs Kubernetes nodes as Docker containers, isolated from host Docker daemon. Local images not automatically available inside k3d cluster. `imagePullPolicy: Always` tries to pull from remote registry (doesn't exist). Developer forgets `k3d image import` step. Helm chart doesn't distinguish local vs remote deployment.

**Consequences:**
- Pod never starts, deployment stuck
- Confusing error message (image pull failure, not "image not found")
- Developers resort to pushing to DockerHub/GHCR (slow, unnecessary)
- Image pull from registry.k8s.io or docker.io hits rate limits
- Local development workflow broken

**Prevention:**
1. **Use k3d image import in development workflow**
   ```bash
   # After building image
   docker build -t atadflow:1.2.0 .
   k3d image import atadflow:1.2.0 -c atadflow-cluster

   # OR use k3d registry (better for iterative development)
   ```

2. **Create local k3d registry** - Automatic image availability
   ```bash
   # Create cluster with registry
   k3d cluster create atadflow \
     --registry-create atadflow-registry:5000 \
     --registry-config registry.yaml

   # Tag and push to local registry
   docker tag atadflow:1.2.0 localhost:5000/atadflow:1.2.0
   docker push localhost:5000/atadflow:1.2.0

   # Helm values
   image:
     repository: k3d-atadflow-registry:5000/atadflow
     tag: "1.2.0"
   ```

3. **Set imagePullPolicy based on environment**
   ```yaml
   # values.yaml
   image:
     repository: atadflow
     tag: "1.2.0"
     pullPolicy: IfNotPresent  # Default for local dev

   # values-prod.yaml
   image:
     pullPolicy: Always
     repository: ghcr.io/myorg/atadflow
   ```

4. **Document k3d-specific setup** - Developer onboarding
   ```markdown
   ## Local Kubernetes Development (k3d)

   ### Setup
   ```bash
   # Create cluster with local registry
   k3d cluster create atadflow --registry-create atadflow-registry:5000

   # Build and import image
   docker build -t localhost:5000/atadflow:dev .
   docker push localhost:5000/atadflow:dev

   # Deploy
   helm upgrade --install atadflow ./chart \
     --set image.repository=k3d-atadflow-registry:5000/atadflow \
     --set image.tag=dev
   ```

   ### Registry Configuration
   The k3d registry is accessible at:
   - From host: `localhost:5000`
   - From k3d: `k3d-atadflow-registry:5000`
   ```

5. **Add Makefile targets for k3d workflow**
   ```makefile
   # Makefile
   .PHONY: k3d-setup k3d-deploy

   k3d-setup:
       k3d cluster create atadflow --registry-create atadflow-registry:5000
       kubectl create namespace atadflow

   k3d-build:
       docker build -t localhost:5000/atadflow:dev .
       docker push localhost:5000/atadflow:dev

   k3d-deploy: k3d-build
       helm upgrade --install atadflow ./chart \
         --namespace atadflow \
         --set image.repository=k3d-atadflow-registry:5000/atadflow \
         --set image.tag=dev \
         --set image.pullPolicy=Always
   ```

6. **Use Skaffold or Tilt for automatic image sync** (advanced)
   ```yaml
   # skaffold.yaml
   apiVersion: skaffold/v4beta6
   kind: Config
   build:
     artifacts:
       - image: atadflow
         docker:
           dockerfile: Dockerfile
     local:
       push: true
       useBuildkit: true
   deploy:
     helm:
       releases:
         - name: atadflow
           chartPath: chart
           setValueTemplates:
             image.repository: "{{.IMAGE_REPO}}"
             image.tag: "{{.IMAGE_TAG}}"
   ```

**Detection:**
- Pod status: `ImagePullBackOff` or `ErrImagePull`
- `kubectl describe pod` → Events: "Failed to pull image: rpc error"
- `docker images` shows image exists locally
- `k3d image list -c atadflow-cluster` doesn't show image

**Phase to address:**
Phase 1 (Helm Chart Basics) - Document k3d registry setup, provide Makefile. Phase 2 (Developer Experience) - Add Skaffold/Tilt config for automatic sync.

**Source confidence:** HIGH - k3d image import is well-known gotcha, extensively documented.

---

### Pitfall 8: Integration Test Flakiness - Race Condition on Helm Install Completion

**What goes wrong:**
Integration test runs `helm install`, immediately tests API endpoint with `curl http://atadflow:8080/api/flows`. Test fails: "Connection refused". Retry succeeds. Flaky test breaks CI pipeline 30% of runs. Root cause: Helm returns success when resources created, not when pods Ready. Test runs before startup probe passes.

**Why it happens:**
`helm install` returns when Kubernetes accepts manifests, not when deployment complete. Pod status: ContainerCreating → Running (but not Ready) → Ready. Startup probe takes 30-60 seconds. PostgreSQL initialization adds 20 seconds (Flyway migrations). Test needs `--wait` flag or explicit readiness check. Race condition: sometimes pod Ready before test starts (passes), sometimes not (fails).

**Consequences:**
- Flaky CI/CD pipeline, retries increase build time
- Developers ignore test failures ("it's just flaky")
- Masks real issues (tests pass on retry even when deployment broken)
- Wastes developer time investigating intermittent failures

**Prevention:**
1. **Use `--wait` flag with adequate timeout**
   ```bash
   helm install atadflow ./chart --wait --timeout 5m

   # OR for upgrade
   helm upgrade --install atadflow ./chart --wait --timeout 5m
   ```

2. **Add explicit readiness check in test script**
   ```bash
   #!/bin/bash
   # integration-test.sh

   # Install chart
   helm upgrade --install atadflow ./chart --wait --timeout 5m

   # Wait for all pods Ready (defense in depth)
   kubectl wait --for=condition=ready pod -l app=atadflow --timeout=300s

   # Wait for service endpoint available
   kubectl wait --for=jsonpath='{.status.loadBalancer.ingress}' service/atadflow --timeout=60s

   # Test API
   curl -f http://atadflow:8080/api/flows || exit 1
   ```

3. **Use Helm test hooks** - Built-in test lifecycle
   ```yaml
   # templates/tests/test-api-available.yaml
   apiVersion: v1
   kind: Pod
   metadata:
     name: test-api-available
     annotations:
       "helm.sh/hook": test
       "helm.sh/hook-delete-policy": hook-succeeded
   spec:
     containers:
       - name: curl
         image: curlimages/curl:8.5.0
         command:
           - curl
           - -f
           - http://{{ include "atadflow.fullname" . }}:8080/api/flows
     restartPolicy: Never
   ```

4. **Implement retry logic in tests** - Tolerate transient failures
   ```bash
   # Test with retry
   for i in {1..30}; do
     if curl -f http://atadflow:8080/api/flows; then
       echo "API available"
       exit 0
     fi
     echo "Waiting for API... ($i/30)"
     sleep 5
   done
   echo "API failed to become available"
   exit 1
   ```

5. **Use Job for post-install validation** - Kubernetes-native
   ```yaml
   # templates/post-install-check.yaml
   apiVersion: batch/v1
   kind: Job
   metadata:
     name: post-install-check
     annotations:
       "helm.sh/hook": post-install,post-upgrade
       "helm.sh/hook-weight": "5"
       "helm.sh/hook-delete-policy": before-hook-creation
   spec:
     template:
       spec:
         containers:
           - name: check
             image: curlimages/curl:8.5.0
             command:
               - /bin/sh
               - -c
               - |
                 for i in $(seq 1 30); do
                   if curl -f http://{{ include "atadflow.fullname" . }}:8080/q/health/ready; then
                     exit 0
                   fi
                   sleep 5
                 done
                 exit 1
         restartPolicy: Never
   ```

6. **Add timeout monitoring in CI**
   ```yaml
   # .github/workflows/integration-test.yml
   - name: Deploy Helm chart
     run: |
       helm upgrade --install atadflow ./chart \
         --wait --timeout 5m \
         --debug  # Verbose output for debugging timeouts
     timeout-minutes: 6  # GitHub Actions timeout > Helm timeout
   ```

**Detection:**
- Test fails intermittently with "Connection refused"
- `kubectl get pods` shows Running but not Ready
- Helm install succeeds, but app not responsive
- CI logs: "Error: timed out waiting for the condition"

**Phase to address:**
Phase 1 (Helm Chart Basics) - Add `--wait` to install commands, implement readiness checks. Phase 2 (CI/CD) - Add Helm test hooks and retry logic.

**Source confidence:** HIGH - Helm install timing and test flakiness widely documented.

---

### Pitfall 9: Telepresence State Corruption - Intercept Persists After `telepresence quit`

**What goes wrong:**
Developer uses Telepresence to debug locally: `telepresence intercept atadflow --port 8080:8080`. Debug session complete, runs `telepresence quit`. Later deploys new Helm upgrade. Pod starts but traffic still routed to (now offline) local machine. Cluster deployment non-functional. Other developers can't access staging environment.

**Why it happens:**
Telepresence modifies Deployment with traffic-agent sidecar and Service to route traffic. `telepresence quit` disconnects local daemon but doesn't always clean up cluster state. Intercept persists in `kubectl get svc` annotations. Helm upgrade doesn't remove Telepresence modifications (unmanaged resources). Traffic-agent sidecar causes pod restart loops if Traffic Manager pod missing.

**Consequences:**
- Staging/dev environment broken for entire team
- Helm rollback doesn't fix (sidecar in Deployment spec)
- Manual cluster cleanup required
- Service endpoint unreachable from within cluster
- Confusing "Connection timeout" errors (traffic routed to void)

**Prevention:**
1. **Always use `telepresence leave` before `telepresence quit`**
   ```bash
   # Proper cleanup sequence
   telepresence leave atadflow
   telepresence quit

   # Verify intercept removed
   kubectl get svc atadflow -o yaml | grep telepresence
   # Should return nothing
   ```

2. **Use namespace-scoped Telepresence** - Isolate per developer
   ```bash
   # Create personal namespace
   kubectl create namespace dev-alice

   # Deploy to personal namespace
   helm install atadflow ./chart -n dev-alice

   # Intercept in personal namespace
   telepresence intercept atadflow -n dev-alice --port 8080:8080
   ```

3. **Document Telepresence cleanup procedure**
   ```markdown
   ## Debugging with Telepresence

   ### Start intercept
   ```bash
   telepresence connect
   telepresence intercept atadflow --port 8080:8080
   ```

   ### Stop intercept (IMPORTANT)
   ```bash
   telepresence leave atadflow  # Remove intercept
   telepresence quit            # Disconnect daemon

   # Verify cleanup
   kubectl get deploy atadflow -o yaml | grep traffic-agent
   # Should return nothing
   ```

   ### Force cleanup (if state corrupted)
   ```bash
   kubectl delete deploy/atadflow
   helm upgrade --install atadflow ./chart --force
   ```
   ```

4. **Add CI check for Telepresence artifacts**
   ```bash
   # pre-deploy.sh
   if kubectl get svc atadflow -o yaml | grep -q telepresence; then
     echo "ERROR: Telepresence intercept active, please run 'telepresence leave atadflow'"
     exit 1
   fi
   ```

5. **Use Telepresence preview URLs** - Safer than global intercepts
   ```bash
   # Personal preview instead of global intercept
   telepresence intercept atadflow --port 8080:8080 --preview-url=true
   # Generates unique URL, doesn't affect team
   ```

6. **Implement namespace cleanup in CI**
   ```yaml
   # .github/workflows/cleanup-dev-namespaces.yml
   name: Cleanup Dev Namespaces
   on:
     schedule:
       - cron: '0 2 * * *'  # Daily 2am
   jobs:
     cleanup:
       runs-on: ubuntu-latest
       steps:
         - name: Delete old dev namespaces
           run: |
             kubectl get ns -l type=dev --sort-by=.metadata.creationTimestamp | \
               head -n -5 | \
               xargs kubectl delete ns
   ```

**Detection:**
- Service annotations show `telepresence.getambassador.io`
- Deployment has `traffic-agent` sidecar container
- `kubectl logs` shows traffic-agent errors
- Traffic routed to non-existent endpoint (developer's laptop offline)

**Phase to address:**
Phase 2 (Developer Experience) - Document Telepresence workflow, add cleanup checks. Phase 3 (Team Collaboration) - Implement namespace-per-developer strategy.

**Source confidence:** MEDIUM - Telepresence state management documented but version-specific behavior varies.

---

### Pitfall 10: Spark Operator CRD Conflict - Multiple SparkApplication Definitions Break Deployment

**What goes wrong:**
Team decides to use Spark Operator for managing Spark Connect cluster. Installs operator: `helm install spark-operator spark-operator/spark-operator`. Later, different team member installs Kubeflow which includes its own Spark Operator. CRD conflict: "CustomResourceDefinition 'sparkapplications.sparkoperator.k8s.io' already exists". Helm install fails. Rollback impossible (CRDs not deleted by Helm uninstall).

**Why it happens:**
Multiple Helm charts bundle Spark Operator with different versions. CRDs are cluster-scoped, can't exist twice. Helm doesn't manage CRD lifecycle by default (`helm.sh/resource-policy: keep`). Operator versions incompatible (Kubeflow uses older fork). Manual CRD deletion risky (breaks existing SparkApplications).

**Consequences:**
- Can't install/upgrade charts
- Cluster-wide outage if wrong CRD version installed
- Requires cluster admin intervention
- Existing Spark jobs fail with schema validation errors
- No clean rollback path

**Prevention:**
1. **Use single source of truth for Spark Operator**
   ```yaml
   # values.yaml - Don't install operator via subchart
   sparkOperator:
     enabled: false  # Install separately via dedicated Helm release

   # Install operator once per cluster
   helm repo add spark-operator https://kubeflow.github.io/spark-operator
   helm install spark-operator spark-operator/spark-operator -n spark-operator --create-namespace
   ```

2. **Document CRD ownership** - Clear responsibility
   ```markdown
   ## Cluster Prerequisites

   Atadflow requires Spark Operator CRDs but does NOT install them.

   ### Installation
   ```bash
   # One-time cluster setup (requires admin)
   kubectl apply -f https://github.com/kubeflow/spark-operator/releases/download/v2.0.0/spark-operator-crds.yaml

   # Install operator
   helm install spark-operator spark-operator/spark-operator \
     -n spark-operator --create-namespace \
     --set webhook.enable=true
   ```

   ### Version Compatibility
   - Atadflow v1.2.x: Spark Operator v2.0.0+
   - CRD API version: v1beta2
   ```

3. **Add CRD version check in chart**
   ```yaml
   # templates/NOTES.txt
   {{- $sparkCRDVersion := (lookup "apiextensions.k8s.io/v1" "CustomResourceDefinition" "" "sparkapplications.sparkoperator.k8s.io").metadata.labels.version }}
   {{- if ne $sparkCRDVersion "v2.0.0" }}
   WARNING: Spark Operator CRD version mismatch
   Expected: v2.0.0
   Found: {{ $sparkCRDVersion }}

   Please upgrade CRDs:
   kubectl apply -f https://github.com/kubeflow/spark-operator/releases/download/v2.0.0/spark-operator-crds.yaml
   {{- end }}
   ```

4. **Use CRD installation job** (if operator not available)
   ```yaml
   # templates/crd-install-job.yaml
   apiVersion: batch/v1
   kind: Job
   metadata:
     name: install-spark-crds
     annotations:
       "helm.sh/hook": pre-install,pre-upgrade
       "helm.sh/hook-weight": "-5"
       "helm.sh/hook-delete-policy": before-hook-creation
   spec:
     template:
       spec:
         serviceAccountName: crd-installer
         containers:
           - name: kubectl
             image: bitnami/kubectl:1.29
             command:
               - kubectl
               - apply
               - -f
               - https://github.com/kubeflow/spark-operator/releases/download/v2.0.0/spark-operator-crds.yaml
         restartPolicy: Never
   ```

5. **RBAC for CRD installation** (if using job)
   ```yaml
   # templates/rbac-crd-installer.yaml
   apiVersion: v1
   kind: ServiceAccount
   metadata:
     name: crd-installer
   ---
   apiVersion: rbac.authorization.k8s.io/v1
   kind: ClusterRole
   metadata:
     name: crd-installer
   rules:
     - apiGroups: ["apiextensions.k8s.io"]
       resources: ["customresourcedefinitions"]
       verbs: ["get", "list", "create", "update", "patch"]
   ---
   apiVersion: rbac.authorization.k8s.io/v1
   kind: ClusterRoleBinding
   metadata:
     name: crd-installer
   roleRef:
     apiGroup: rbac.authorization.k8s.io
     kind: ClusterRole
     name: crd-installer
   subjects:
     - kind: ServiceAccount
       name: crd-installer
       namespace: {{ .Release.Namespace }}
   ```

6. **Alternative: Don't use Spark Operator** - Keep subprocess approach
   ```markdown
   ## Architecture Decision: Subprocess vs Spark Operator

   **Decision:** Continue using ProcessBuilder subprocess execution (v1.0 approach)

   **Rationale:**
   - Simpler deployment (no operator dependency)
   - Fewer cluster-level permissions required
   - Avoids CRD version conflicts
   - Proven approach (v1.0, v1.1 shipped successfully)

   **Trade-off:** No Spark job CRD-based management
   ```

**Detection:**
- Helm install fails: "CRD already exists"
- `kubectl get crd` shows multiple spark-related CRDs
- Operator logs: "version mismatch" or schema validation errors
- SparkApplication resources fail validation

**Phase to address:**
Phase 1 (Helm Chart Basics) - Document operator as external dependency, add version check. Phase 3 (Spark Operator Integration) - Only if operator adoption decided, implement CRD management strategy.

**Source confidence:** HIGH - CRD conflicts common Kubernetes pitfall, well-documented in operator patterns.

---

## Moderate Pitfalls

### Pitfall 11: Values.yaml Complexity Explosion

**What goes wrong:**
Helm chart starts simple. Add production overrides, dev overrides, staging overrides. values.yaml reaches 500 lines. Nested subchart overrides (postgresql.primary.persistence.size) 5 levels deep. Developers can't find correct value to change. Accidentally override wrong value, break deployment.

**Prevention:**
- Use layered values files: `values.yaml` (defaults), `values-dev.yaml`, `values-prod.yaml`
- Document value purpose with inline comments
- Group related values under clear sections
- Limit subchart override depth to 3 levels
- Use `global.*` for cross-cutting concerns

**Phase to address:** Phase 1 (Helm Chart Basics) - Establish values structure conventions.

---

### Pitfall 12: Secret Management Anti-Pattern - Passwords in values.yaml

**What goes wrong:**
values.yaml contains `postgresql.auth.password: atadflow` in plaintext. File committed to Git. Production password leaked. Security audit fails.

**Prevention:**
- Use Kubernetes Secrets, reference in templates
- Use `helm secrets` plugin or Sealed Secrets
- Document: "Never commit real credentials to values.yaml"
- Use separate values-secrets.yaml (gitignored)
- For prod: integrate with HashiCorp Vault or AWS Secrets Manager

**Phase to address:** Phase 2 (Production Hardening) - Implement secret management strategy.

---

### Pitfall 13: Resource Limit Not Set - Unbounded Memory Consumption

**What goes wrong:**
Helm chart deployed without `resources.limits.memory`. Java heap grows unbounded. Node memory exhausted. Multiple pods OOMKilled, cascade failure.

**Prevention:**
- Always set `resources.limits` and `resources.requests`
- Use Vertical Pod Autoscaler recommendations
- Test with realistic workload to calibrate limits
- Monitor memory usage, adjust limits based on metrics

**Phase to address:** Phase 1 (Helm Chart Basics) - Set conservative limits initially.

---

### Pitfall 14: Namespace Hardcoded - Multi-Tenant Deployment Breaks

**What goes wrong:**
Template hardcodes `namespace: default`. Can't deploy to `staging` namespace. Multi-tenant deployment impossible.

**Prevention:**
- Use `{{ .Release.Namespace }}` in templates
- Never hardcode namespace
- Test deployment to non-default namespace
- Document namespace creation in README

**Phase to address:** Phase 1 (Helm Chart Basics) - Template all namespace references.

---

### Pitfall 15: Ingress TLS Certificate Not Validated - MITM Risk

**What goes wrong:**
Ingress configured with self-signed certificate. Cert-manager not installed. Browser shows certificate warning. Users ignore warning, train to accept insecure connections.

**Prevention:**
- Use cert-manager for automatic TLS
- Document Let's Encrypt integration
- Test certificate renewal (90-day expiry)
- Monitor certificate expiration

**Phase to address:** Phase 3 (Production Hardening) - Integrate cert-manager.

---

## Integration Gotchas

Common mistakes when connecting to external services.

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| PostgreSQL subchart | Random password on upgrade breaks PVC access | Pin password in values.yaml, never use random |
| Spark Connect DNS | Use `localhost` in K8s env | Use Service DNS: `spark-connect.namespace.svc.cluster.local` |
| Temp file creation | Write to read-only root filesystem | Mount emptyDir to /tmp and configure java.io.tmpdir |
| Image pull | Expect k3d to see local Docker images | Use `k3d image import` or k3d registry |
| Health probes | Use same timing for startup and liveness | Use startup probe with high failureThreshold, liveness with low |
| Helm dependencies | Use version ranges (~16.0.0) | Pin exact versions, commit Chart.lock |
| Telepresence | Forget to run `telepresence leave` | Always leave intercept before quit, verify cleanup |
| Spark Operator CRDs | Install via subchart, cause conflicts | Install CRDs separately, document as prerequisite |

## Performance Traps

Patterns that work at small scale but fail as usage grows.

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| No pod resource limits | Node memory exhaustion, cascade failures | Set limits.memory with subprocess headroom | >3 concurrent jobs per node |
| Single PostgreSQL replica | Downtime during restarts, slow queries | Use PostgreSQL with replicas, read-only queries to replicas | >100 concurrent users |
| No horizontal pod autoscaling | Slow response during traffic spikes | Add HPA based on CPU/memory metrics | >50 req/s |
| emptyDir without size limit | Node disk fills, all pods evicted | Set emptyDir.sizeLimit to prevent unbounded growth | Temp files >10GB |
| Liveness probe too aggressive | Restart loops during high load | Increase failureThreshold and timeoutSeconds | CPU >80% sustained |
| Single Spark Connect instance | Job queue backlog, resource contention | Deploy Spark Connect with autoscaling | >20 concurrent streaming jobs |

## Security Mistakes

Domain-specific security issues beyond general web security.

| Mistake | Risk | Prevention |
|---------|------|------------|
| Running as root user | Container breakout escalates to node root | Set securityContext.runAsNonRoot: true |
| Writable root filesystem | Malware persistence, container compromise | Set readOnlyRootFilesystem: true with emptyDir for /tmp |
| No network policies | Lateral movement after pod compromise | Implement NetworkPolicy to restrict pod-to-pod traffic |
| Secrets in environment variables | Process listing exposes credentials | Use Kubernetes Secrets with file mounts, not env vars |
| Default service account with cluster-admin | RBAC escalation via compromised pod | Create dedicated ServiceAccount with minimal RBAC |
| Exposing internal ports publicly | Unauthorized access to PostgreSQL, Spark Connect | Use ClusterIP for internal services, Ingress with auth for public |
| No pod security standards | Privileged pods, host network access | Enable Pod Security Admission (restricted policy) |

## "Looks Done But Isn't" Checklist

Things that appear complete but are missing critical pieces.

- [ ] **Resource limits:** Limits set but no subprocess memory headroom → OOMKilled under load
- [ ] **Startup probe:** Liveness probe works but startup probe missing → Restart loops on cold start
- [ ] **Persistence:** PVC created but no Retain policy → Data loss on Helm uninstall
- [ ] **Image pull:** Chart installs but ImagePullPolicy: Always with no registry → Works in CI, fails in k3d
- [ ] **Temp files:** App runs but readOnlyRootFilesystem breaks subprocess → Fails only in hardened security context
- [ ] **DNS resolution:** Works in Docker Compose, breaks in K8s → Localhost URLs not templated
- [ ] **Health checks:** /q/health/live responds but doesn't check Spark Connect → Liveness passes with broken connectivity
- [ ] **Secrets:** Chart installs but passwords in plaintext values.yaml → Security audit failure
- [ ] **Subchart versions:** Deploys today, breaks on `helm dependency update` → Chart.lock not committed
- [ ] **Telepresence cleanup:** Intercept stops but sidecar persists → Staging broken for team

## Recovery Strategies

When pitfalls occur despite prevention, how to recover.

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Pod OOMKilled | LOW | Increase memory limits, redeploy, monitor usage |
| Spark Connect DNS failure | LOW | Fix URL in values.yaml, `helm upgrade`, verify connectivity |
| PostgreSQL data loss | HIGH | Restore from backup PVC snapshot, replay WAL if available, or accept data loss |
| Image pull failure in k3d | LOW | `k3d image import`, redeploy, or setup k3d registry |
| Startup probe killing pod | LOW | Increase failureThreshold, redeploy, optimize startup time |
| Helm dependency version drift | MEDIUM | Pin exact version in Chart.yaml, `helm dependency update`, test in staging |
| Telepresence state corruption | MEDIUM | `kubectl delete deploy`, `helm upgrade --force`, verify cleanup |
| CRD conflict | HIGH | Requires cluster admin, delete conflicting CRD (risky), reinstall correct version |
| Read-only filesystem temp fail | LOW | Add emptyDir volume for /tmp, redeploy |
| Values.yaml secrets leaked | HIGH | Rotate all credentials, revoke Git history, implement secrets management |

## Pitfall-to-Phase Mapping

How roadmap phases should address these pitfalls.

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| Subprocess OOMKilled | Phase 1: Helm Basics | Load test with 5 concurrent jobs, no OOMKilled |
| gRPC DNS failure | Phase 1: Helm Basics | Deploy to K8s, submit job, verify Spark Connect connection |
| PostgreSQL data loss | Phase 1: Helm Basics | Helm uninstall/reinstall, verify data persists |
| Temp file read-only | Phase 1: Helm Basics | Enable readOnlyRootFilesystem, submit job, verify success |
| Health probe timing | Phase 1: Helm Basics | Cold start deployment, pod becomes Ready without restart |
| Helm dependency drift | Phase 1: Helm Basics | Chart.lock committed, `helm dependency list` shows pinned versions |
| k3d image pull | Phase 1: Helm Basics | Document k3d workflow, test in fresh cluster |
| Integration test flaky | Phase 1: Helm Basics | CI runs 10 times, 100% success rate |
| Telepresence state | Phase 2: Dev Experience | Document cleanup, add CI check for artifacts |
| Spark Operator CRD | Phase 3: Spark Operator | Only if adopting operator, document external install |
| Values complexity | Phase 2: Production Hardening | values.yaml <200 lines, layered files for environments |
| Secret management | Phase 2: Production Hardening | No plaintext passwords, integrate secrets manager |
| Resource limits | Phase 1: Helm Basics | Set conservative limits, VPA recommendations later |
| Namespace hardcoded | Phase 1: Helm Basics | Deploy to non-default namespace |
| TLS certificates | Phase 3: Production Hardening | Cert-manager integration, auto-renewal |

## Phase-Specific Warnings

| Phase Topic | Likely Pitfall | Mitigation |
|-------------|---------------|------------|
| Helm Chart Basics (Phase 1) | Forgetting to import images to k3d | Document workflow, provide Makefile targets |
| Helm Chart Basics (Phase 1) | PostgreSQL subchart data loss | Enable persistence, pin version, commit Chart.lock |
| Helm Chart Basics (Phase 1) | Subprocess OOMKilled under load | Reserve 30-40% memory headroom for Python |
| Helm Chart Basics (Phase 1) | Temp file creation fails with read-only FS | Mount emptyDir to /tmp before enabling security |
| Production Hardening (Phase 2) | Secrets in values.yaml committed to Git | Implement secrets management before prod deploy |
| Production Hardening (Phase 2) | Certificate expiration unnoticed | Integrate cert-manager with monitoring |
| Spark Operator (Phase 3) | CRD version conflicts | Document as external dependency, don't bundle |
| Integration Testing (Phase 2) | Flaky tests from timing | Use `--wait` flag, explicit readiness checks |
| Developer Experience (Phase 2) | Telepresence state corruption | Document cleanup procedure, namespace isolation |

## Sources

**Kubernetes Memory Management and OOMKilled:**
- [Sizing Kubernetes pods for JVM apps without fearing the OOM Killer](https://varoa.net/jvm/kubernetes/memory/docker/oomkiller/2019/05/29/k8s-and-java.html) - HIGH confidence
- [How to Fix OOMKilled Kubernetes Error (Exit Code 137)](https://komodor.com/learn/how-to-fix-oomkilled-exit-code-137/) - HIGH confidence
- [Kubernetes Memory Metrics: Deep Dive into RSS, WSS & Linux Cache](https://itnext.io/from-rss-to-wss-navigating-the-depths-of-kubernetes-memory-metrics-4d7d77d8fdcb) - HIGH confidence
- [Understanding resource limits in kubernetes: memory](https://medium.com/@betz.mark/understanding-resource-limits-in-kubernetes-memory-6b41e9a955f9) - HIGH confidence

**gRPC Load Balancing and DNS in Kubernetes:**
- [gRPC Load Balancing on Kubernetes without Tears](https://kubernetes.io/blog/2018/11/07/grpc-load-balancing-on-kubernetes-without-tears/) - HIGH confidence
- [How three lines of configuration solved our gRPC scaling issues in Kubernetes](https://medium.com/jamf-engineering/how-three-lines-of-configuration-solved-our-grpc-scaling-issues-in-kubernetes-ca1ff13f7f06) - HIGH confidence
- [Load Balancing in Kubernetes and how to use gRPC protocol](https://medium.com/garantibbva-teknoloji/load-balancing-in-kubernetes-and-how-to-use-grpc-protocol-7735ae7faabb) - HIGH confidence

**Kubernetes Temporary Storage and emptyDir:**
- [Understanding Kubernetes emptyDir](https://decisivedevops.com/understanding-kubernetes-emptydir-with-3-practical-use-cases-960f550e0e34/) - HIGH confidence
- [Read-only filesystems in Docker and Kubernetes](https://www.thorsten-hans.com/read-only-filesystems-in-docker-and-kubernetes/) - HIGH confidence
- [Kubernetes - Utilising tmpfs volumes](https://reece.tech/posts/tmpfs-in-kubernetes/) - MEDIUM confidence

**Helm PostgreSQL Subchart Pitfalls:**
- [Bitnami PostgreSQL chart](https://github.com/bitnami/charts/tree/main/bitnami/postgresql) - HIGH confidence
- [Helm: access to postgres data is lost after upgrade](https://github.com/requarks/wiki/discussions/3844) - HIGH confidence
- [Managing Helm Chart Dependencies and Subcharts](https://oneuptime.com/blog/post/2026-01-17-helm-chart-dependencies-subcharts/view) - HIGH confidence

**Kubernetes Health Probes:**
- [Configure Liveness, Readiness and Startup Probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/) - HIGH confidence
- [SmallRye Health - Quarkus](https://quarkus.io/guides/smallrye-health) - HIGH confidence
- [Kubernetes Health Checks and Probes](https://betterstack.com/community/guides/monitoring/kubernetes-health-checks/) - HIGH confidence

**k3d Image Management:**
- [Using Image Registries - k3d](https://k3d.io/v5.4.4/usage/registries/) - HIGH confidence
- [Use local images with k3d without any imports](https://thoughtexpo.com/k3d-images/) - MEDIUM confidence
- [K3d image import](https://k3d.io/v5.3.0/usage/commands/k3d_image_import/) - HIGH confidence

**Telepresence:**
- [Telepresence GitHub](https://github.com/telepresenceio/telepresence) - HIGH confidence
- [Intercept a service in your own environment](https://www.getambassador.io/docs/telepresence/latest/howtos/intercepts) - HIGH confidence
- [How to Debug Locally with Telepresence in Kubernetes](https://oneuptime.com/blog/post/2026-01-19-kubernetes-telepresence-local-debugging/view) - HIGH confidence

**Helm Dependency Management:**
- [Dependencies - Helm Best Practices](https://helm.sh/docs/chart_best_practices/dependencies/) - HIGH confidence
- [Helm Advanced Guide: Master Values, Overrides, and Chart Dependencies](https://medium.com/@bavicnative/helm-advanced-values-overrides-and-dependencies-35976b996143) - MEDIUM confidence
- [Helm Charts: The Complete Guide for 2026](https://devtoolbox.dedyn.io/blog/helm-charts-complete-guide) - MEDIUM confidence

**Kubernetes Integration Testing:**
- [Container Testing in Kubernetes: Tools & Best Practices](https://testkube.io/blog/how-to-run-container-tests-kubernetes-best-practices-tools-examples) - MEDIUM confidence
- [K3s Module - Testcontainers for Java](https://java.testcontainers.org/modules/k3s/) - HIGH confidence

**Spark Operator:**
- [Running Spark on Kubernetes - Spark 4.1.0 Documentation](https://spark.apache.org/docs/latest/running-on-kubernetes.html) - HIGH confidence
- [How to Set Up Kubernetes Batch Processing with Apache Spark Operator](https://oneuptime.com/blog/post/2026-02-09-batch-processing-spark-operator/view) - HIGH confidence
- [Quick Start Guide - spark-operator](https://kubeflow.github.io/spark-operator/docs/quick-start-guide.html) - HIGH confidence

**Quarkus in Kubernetes:**
- [Faster, Lower, Better with Quarkus in k8s](https://www.sokube.ch/post/faster-lower-better-with-quarkus-in-k8s) - MEDIUM confidence
- [Quarkus for Spring developers: Kubernetes-native design patterns](https://developers.redhat.com/articles/2021/10/11/quarkus-spring-developers-kubernetes-native-design-patterns) - HIGH confidence

**PySpark Subprocess:**
- [PySpark Internals - Spark](https://cwiki.apache.org/confluence/display/SPARK/PySpark+Internals/) - HIGH confidence
- [Containerization of PySpark Using Kubernetes](https://www.kdnuggets.com/2020/08/containerization-pyspark-kubernetes.html) - MEDIUM confidence

---

*Research complete: 2026-02-15*
*Confidence: HIGH (based on official Kubernetes/Helm/Spark documentation and verified community sources)*
*Focus: Pitfalls specific to adding Helm chart K8s deployment to subprocess-executing Java app*

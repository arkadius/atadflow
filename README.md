# atad-flow

Visual flow designer for PySpark Structured Streaming pipelines. Design pipelines with drag-and-drop nodes, inline parameter editing, and per-node Python code, then execute them as Spark jobs.

## Prerequisites

- Java 21+
- Node.js 20+
- Docker (for PostgreSQL)

## Development Setup

### 1. Start PostgreSQL

```bash
docker compose up -d
```

This starts a PostgreSQL 16 instance on port 5432 with database `atadflow`.

### 2. Start the backend

```bash
cd backend
./gradlew quarkusDev
```

Quarkus starts on http://localhost:8080 with:
- Flyway migrations applied automatically
- Swagger UI at http://localhost:8080/q/swagger-ui
- Live reload enabled

### 3. Start the frontend

```bash
cd frontend
npm install
npm run dev
```

Vite dev server starts on http://localhost:5173 with a proxy forwarding `/api` requests to the backend.

### 4. Open the app

Go to http://localhost:5173/flows to start designing pipelines.

## Running Tests

```bash
cd backend
./gradlew test
```

Tests use Quarkus Dev Services which automatically starts a PostgreSQL container via Testcontainers — no manual database setup needed.

## Docker Deployment

### Building the Docker Image

The project includes a multi-stage Dockerfile that builds both frontend and backend:

```bash
docker build -t atadflow:latest .
```

This creates a ~450MB image containing:
- Quarkus backend application
- React frontend (bundled into backend JAR)
- Python 3.12 + PySpark 4.0.2 runtime

**Build time:** 2-4 minutes (first build), ~1 minute (subsequent with Docker layer caching)

### Running with Docker Compose

Start the complete stack (PostgreSQL + Spark Connect + Atadflow):

```bash
# Build and start all services
docker compose up -d

# Watch application logs
docker compose logs -f atadflow

# Check service health
docker compose ps

# Stop all services
docker compose down
```

Access the application at **http://localhost:8080**

### Configuration

Override configuration via environment variables in `docker-compose.yml`:

- `DATABASE_URL` - PostgreSQL JDBC URL (default: `jdbc:postgresql://postgres:5432/atadflow`)
- `SPARK_CONNECT_URL` - Spark Connect endpoint (default: `sc://spark-connect:15002`)
- `QUARKUS_DATASOURCE_USERNAME` - Database username
- `QUARKUS_DATASOURCE_PASSWORD` - Database password

### Health Checks

The application exposes health check endpoints:

- `/q/health/live` - Liveness probe (is app running?)
- `/q/health/ready` - Readiness probe (can app accept traffic?)
- `/q/health` - Combined health status

Docker Compose uses these endpoints to ensure services start in correct order:
postgres → spark-connect → atadflow

### Integration Testing

Run end-to-end tests against the built Docker image:

```bash
cd backend
./gradlew quarkusIntegrationTest
```

These tests use Testcontainers to spin up the Docker image and validate:
- Frontend accessible and serves React app
- Backend API endpoints work
- Job submission and execution through Spark Connect
- Health check endpoints respond correctly

## Project Structure

```
atadflow/
├── docker-compose.yml          # PostgreSQL 16
├── backend/                    # Quarkus (Java 25, Gradle 9)
│   └── src/main/java/io/atadflow/
│       ├── entity/             # Flow, FlowNode, FlowEdge, Job
│       ├── dto/                # Request/response records
│       ├── mapper/             # Entity ↔ DTO conversion
│       ├── resource/           # JAX-RS endpoints
│       ├── service/            # Business logic, code generation
│       ├── nodetype/           # Node type SPI + built-in providers
│       └── exception/          # Error handling
└── frontend/                   # React 19, Vite, TypeScript
    └── src/
        ├── api/                # Fetch wrapper
        ├── types/              # TypeScript interfaces
        ├── hooks/              # React hooks
        ├── components/         # Flow canvas, editor, jobs
        └── pages/              # Flow list, flow editor
```

## API Overview

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/node-types` | List registered node type descriptors |
| GET | `/api/flows` | List all flows |
| GET | `/api/flows/{id}` | Get flow with nodes and edges |
| POST | `/api/flows` | Create a new flow |
| PUT | `/api/flows/{id}` | Update flow (full replace of nodes/edges) |
| DELETE | `/api/flows/{id}` | Delete flow |
| GET | `/api/flows/{id}/code` | Generate PySpark pipeline code |
| GET | `/api/jobs?flowId=` | List jobs (optionally filtered by flow) |
| GET | `/api/jobs/{id}` | Get job details |
| POST | `/api/jobs` | Submit a job |
| POST | `/api/jobs/{id}/cancel` | Cancel a running job |

## Node Type SPI

Node types are extensible. Implement `NodeTypeProvider` as a CDI bean to register custom node types:

```java
@ApplicationScoped
public class MyNodeTypeProvider implements NodeTypeProvider {
    @Override
    public List<NodeTypeDescriptor> getNodeTypes() {
        return List.of(
            new NodeTypeDescriptor("my-node", "My Node", inputs, outputs, configSchema, codeTemplate)
        );
    }
}
```

Six built-in node types are included: `read-stream`, `filter`, `select`, `group-by`, `with-watermark`, `write-stream`.

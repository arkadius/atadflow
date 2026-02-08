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

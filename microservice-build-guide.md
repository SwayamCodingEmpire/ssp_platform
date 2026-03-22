# Microservice Build Guide — SSP Platform
## Phase-by-Phase Execution Plan for Someone New to Microservices

> **How to use this document:**
> Read each phase fully before starting it. Understand the *why* before touching code.
> Each phase has a clear "done when" checkpoint — do not move to the next phase until you hit it.
> The order is not arbitrary. Each phase unlocks the next.

---

## The Mental Model First

Before touching any code, understand this fundamental shift:

**Monolith thinking:** One app, one database, method calls between components, one deploy.

**Microservice thinking:** Many small apps, each with its own database, talking to each other
over the network (REST or events), each deployed independently.

The biggest trap people fall into: they start splitting code before they understand *why* services
are separated the way they are. So read this first:

### Why these 8 services, and not 4 or 20?

The rule is: **split by business capability, not by technical layer.**

Wrong split: "auth-layer, data-layer, api-layer" — this is just a monolith with network calls.

Right split: "auth service handles identity, manuscript service handles uploads, analysis service
handles AI extraction" — each owns a complete business function end to end.

The 8 services in this platform are split this way:
- **Auth Service** — owns user identity. Nothing else touches passwords or JWTs.
- **API Gateway** — owns routing and security enforcement. The single front door.
- **Subscription Service** — owns billing and limits. The money layer.
- **Manuscript Service** — owns the raw content (projects, chapters, files). The storage layer.
- **Analysis Service** — owns AI extraction (characters, scenes, relationships). The intelligence layer.
- **Translation Service** — owns language conversion. The output layer.
- **Coaching Service** — owns author feedback and narrative health reports. The product layer.
- **Notification Service** — owns all outbound communication. The alerting layer.

Each service is independently deployable, independently scalable, and independently replaceable.
If your translation provider changes, you only touch the Translation Service. Nothing else changes.

### Why Kafka between services?

When Manuscript Service finishes ingesting a chapter, Analysis Service needs to know about it.
You have two options:

**Option A — Direct REST call:** Manuscript calls Analysis's API.
Problem: Manuscript now *depends* on Analysis being up. If Analysis is down, the upload fails.
They are coupled.

**Option B — Kafka event:** Manuscript publishes "chapter uploaded" to a Kafka topic.
Analysis subscribes and reacts when it's ready.
Manuscript doesn't care if Analysis is up or down. They are decoupled.

This is the core reason for Kafka. It's not about performance. It's about **decoupling**.

---

## Phase 0 — Foundation

### What you're doing

Setting up the skeleton: the parent project, the infrastructure (databases + Kafka), and 8 empty
Spring Boot apps that start without errors. No business logic yet.

> **Critical before writing any code:** Settle the domain hierarchy first.
> The correct entity hierarchy is: **Tenant → Project → Book → Chapter**
> This must be reflected in the database schema from day one. Getting it wrong and
> retrofitting it later (e.g. changing `chapter.project_id` → `chapter.book_id`) means
> rewriting migrations, mappers, adapters, and tests across multiple services.
> Draw the entity diagram. Agree on table names. Then build Phase 0.

### Why this phase exists

You cannot build a house without foundations. The infrastructure phase forces you to:
1. Understand the project structure (parent POM + modules)
2. Get Docker working and understand why each service has its own DB
3. Understand port assignments and service naming conventions
4. Confirm IntelliJ can run all 8 apps at once before you add complexity

Skipping this and jumping to Auth is a mistake. You'll spend hours debugging infrastructure
while simultaneously debugging business logic.

### What you'll set up

**Step 1 — Parent Maven project (`ssp-platform`)**

Create a Maven project with `packaging=pom`. This is the container for all 8 modules.
It has no code of its own — just a `pom.xml` that lists all submodules and defines shared
dependency versions. Think of it as the "workspace" in IntelliJ terms.

Why a parent POM? Because you don't want to define Spring Boot version in 8 different places.
You define it once in the parent. All children inherit it. When you upgrade Spring Boot,
you change one line.

**Step 2 — Docker Compose file**

Write one `docker-compose.yml` at the root of `ssp-platform` that starts:
- 6 PostgreSQL databases (one per service that needs a DB — Auth, Subscription, Manuscript,
  Analysis, Translation, Coaching)
- 1 Kafka instance (KRaft mode — no ZooKeeper needed in modern Kafka)

Why separate databases? This is the most important rule in microservices: **no shared databases**.
If Auth and Manuscript shared one DB, they'd be coupled at the data level even if their code
is separate. Database separation enforces true independence.

You'll run `docker-compose up -d` once. Containers stay running in the background.
You never stop them during development.

**Step 3 — 8 empty Spring Boot modules**

In IntelliJ: right-click parent → New → Module → Spring Initializr for each service.

At this stage, each module needs only:
- `spring-boot-starter-web` (so it starts an HTTP server)
- `spring-boot-starter-data-jpa` (for services that have a DB)
- PostgreSQL driver
- The correct port in `application.yaml`

No business logic. No domain models. Just an app that starts.

**Step 4 — Shared JWT secret**

All services that need to verify JWT tokens must share the same secret key.
This goes in each service's `application.yaml` as an environment variable reference:
`${JWT_SECRET}`. You define the actual value in a `.env` file at the root level.

This teaches you a key microservice concept: **shared configuration via environment variables**,
not hardcoded values.

### What "done" looks like

- `docker-compose up -d` completes with no errors
- All 6 databases are accessible (you can connect via IntelliJ's Database panel)
- Kafka is running on port 9092
- All 8 Spring Boot apps start without errors (`./mvnw spring-boot:run` in each module,
  or use IntelliJ's Services panel to start all 8)
- No compilation errors anywhere

### What you'll learn in this phase

- Maven multi-module project structure
- Docker Compose for local infrastructure
- Why microservices have separate databases
- IntelliJ Services panel for running multiple Spring Boot apps

---

## Phase 1 — Identity and Routing

### What you're doing

Building the Auth Service (login/register/JWT) and the API Gateway (the front door that
enforces authentication on all routes).

### Why this phase comes second (right after foundation)

You cannot build anything else without answering: "who is this request from?"

Every subsequent service (Manuscript, Analysis, Translation, Coaching) needs to know the
current user's ID and subscription tier. The Auth Service is the source of truth for identity.
The Gateway enforces it. Everything else builds on top of this.

If you build Manuscript Service first without auth, you'll have to go back and retrofit auth.
Retrofitting auth is always messy. Build it first.

### Auth Service

**What it owns:** User table, password hashing, JWT issuance, token refresh.

The Auth Service has one database: `auth_db`. It stores:
- User accounts (email, hashed password, role)
- Refresh tokens (for getting new access tokens without re-logging in)

It exposes:
- `POST /auth/register` — creates account, returns JWT
- `POST /auth/login` — validates credentials, returns JWT + refresh token
- `POST /auth/refresh` — takes refresh token, returns new JWT

The JWT it issues contains: `userId`, `email`, `tier` (FREE/BASIC/PRO).
This is the payload that all other services will read without calling Auth Service again.

**Why JWT and not sessions?**

Sessions require all services to share a session store (Redis or DB). JWT is self-contained —
the payload is encoded in the token itself. Any service can verify and read it using the shared
secret key without making a network call. This is essential for microservices.

### API Gateway

**What it owns:** All incoming traffic. Routing rules. JWT verification.

The Gateway is built with **Spring Cloud Gateway** (not a regular Spring Boot MVC app).
It is a reactive proxy — it doesn't process business logic, it just routes requests.

It does three things:
1. For `/auth/**` routes → forward to Auth Service without any JWT check (login/register are public)
2. For all other routes → validate the JWT in the `Authorization: Bearer <token>` header
3. If JWT is valid → inject `X-User-Id` and `X-User-Tier` headers into the forwarded request

Why inject headers instead of passing the raw JWT downstream?
Because downstream services should not have to parse JWT themselves. The Gateway has already
verified it. The downstream service just reads `X-User-Id` from the header — simple and safe.

The Gateway routes might look like:
- `/api/manuscripts/**` → Manuscript Service (port 8083)
- `/api/analysis/**` → Analysis Service (port 8084)
- `/api/translation/**` → Translation Service (port 8085)
- `/api/coaching/**` → Coaching Service (port 8086)

### What "done" looks like

- `POST /auth/register` through Gateway creates a user and returns a JWT
- `POST /auth/login` returns a JWT
- `GET /api/manuscripts/health` with no JWT → **401 Unauthorized** from Gateway
- `GET /api/manuscripts/health` with valid JWT → **200 OK** from Manuscript Service
  (even though Manuscript Service does nothing yet — the routing works)

### What you'll learn in this phase

- JWT structure: header.payload.signature — what's in each part
- Spring Cloud Gateway route configuration (YAML-based routing rules)
- JWT filter / global filter in Spring Cloud Gateway
- Why stateless auth scales better than sessions
- The Gateway pattern in microservices

---

## Phase 2 — Manuscript Ingestion

### What you're doing

Moving project CRUD and chapter upload/ingestion from the SSP monolith into the Manuscript Service.
At the end of this phase, uploading a chapter publishes a Kafka event.

### Why this phase comes third

The Manuscript Service is the **entry point for all content**. Analysis, Translation, and Coaching
all react to content that Manuscript Service has accepted. You cannot build downstream services
until the upstream data source exists.

Also, this is your existing SSP code — you know it well. Starting with familiar code in the new
architecture helps you understand the structural differences without also learning new business logic.

### What you're moving

From the SSP monolith, the Manuscript Service takes ownership of:
- `ManageProjectUseCase` and `ManageProjectService` (project CRUD)
- `IngestChapterUseCase` and `IngestChapterService` (chapter text + file upload)
- All project and chapter JPA entities, repositories, adapters, mappers
- Apache Tika document parsing (`TikaDocumentParserAdapter`)
- Content-hash deduplication (already implemented)

You keep the hexagonal architecture as-is. The internal structure doesn't change.
What changes is the **outbound event**: instead of `ApplicationEventPublisher.publish(event)`,
you now call `KafkaTemplate.send("chapter.ingested", event)`.

### The key new concept: Kafka producer

After saving a chapter, the Manuscript Service publishes a `chapter.ingested` event to Kafka.
The event payload contains: `chapterId`, `projectId`, `chapterNumber`, `analysisEnabled`.

This is the same information that was in `ChapterIngestedEvent` in the monolith —
just now it goes to Kafka instead of the in-process Spring event bus.

You'll implement this through the existing `EventPublisherPort`. The port interface doesn't change.
Only the adapter changes — from `SpringEventPublisher` to a `KafkaEventPublisher`.

This is hexagonal architecture paying off: the application service (`IngestChapterService`)
doesn't know or care how the event is published.

### What the Manuscript Service does NOT do

- It does NOT call Analysis Service directly
- It does NOT know whether analysis succeeded or failed
- It does NOT care about translation or coaching

It uploads, saves, publishes an event, and moves on. What happens next is not its concern.
This is the single responsibility principle at the service level.

### What "done" looks like

- `POST /api/manuscripts/projects` creates a project (through Gateway with JWT)
- `POST /api/manuscripts/chapters` ingests a chapter
- After ingestion, open IntelliJ's Kafka plugin, inspect the `chapter.ingested` topic —
  the event payload appears there with correct chapterId and projectId
- Duplicate upload returns the existing chapter without publishing a new event (deduplication works)

### What you'll learn in this phase

- Kafka producer setup in Spring Boot (`spring-kafka`)
- KafkaTemplate: how to send messages to a topic
- JSON serialization of events (event as a Java record → JSON → Kafka)
- Why the port abstraction made the switch from Spring events to Kafka trivial
- Inter-service data ownership: Manuscript owns projects and chapters — no other service writes to these tables

---

## Phase 3.5 — Book Completion Pipeline

### What you're doing

After all chapters of a Book are analyzed, run a one-time pipeline that generates
book-level aggregate entities: `BookSummary`, `BookCharacter`, `BookRelationship`,
`BookWorldState`, `BookKeyScene`, and the RAPTOR hierarchical index.

### Why this phase exists as its own step

Within-book analysis (Phase 3) gives you per-chapter granularity — CharacterState snapshots,
Scene records, WorldState updates. That's great for working *within* a book.

But when an author starts Book 2, the question is not "what happened in Chapter 47?"
It's "who is this character now? what is the world like? what themes has this series established?"
Answering that by RAGging across 300 chapters is expensive and incomplete.

Book completion compresses all of that into a clean, pre-computed handoff package.
One SQL read at translation/coaching time — no LLM calls, no RAG hops.

### What gets built

- **ProjectSeriesSummary** — a rolling ~500-word compressed series context. Updated after each
  book completes. Prompt: "Given previous series summary + this book's summary, write an updated
  500-word context for translators and coaches." One LLM call per book.
- **BookCharacter** — roll up all `CharacterState` snapshots for each character in this book
  into a single record: who they are as a person, their final state, their key moments.
- **BookRelationship** — final relationship state and a one-line dynamics summary per pair.
- **BookWorldState** — compressed world state at the end of this book.
- **BookKeyScene** — 10–20 scenes selected by LLM as most narratively significant going forward.
- **RAPTOR tree** — chunk all chapters → cluster → summarize per cluster → store all levels in
  pgvector with `level` (0–3) and `book_id` metadata. Built with Gemini 2.0 Flash (cheap).

### What "done" looks like

- All chapters of a test book reach `ANALYZED`
- Book completion pipeline triggers automatically
- `GET /api/analysis/books/{id}/context-package` returns the full package (BookSummary +
  BookCharacter list + BookWorldState + BookKeyScene list)
- pgvector has RAPTOR entries with `level=0,1,2,3` for that book

### What you'll learn

- Recursive summarization (RAPTOR algorithm — implement in Java with Apache Commons Math for k-means)
- Book status lifecycle management
- Pre-computation vs on-demand retrieval trade-offs
- Embedding custom entity types in pgvector with metadata filtering

---

## Phase 3 — Analysis Pipeline

### What you're doing

Building the Analysis Service that listens to `chapter.ingested` Kafka events and runs the full
AI extraction pipeline (character extraction, scene analysis, world state, embeddings).

### Why this phase comes fourth

Analysis needs chapters to exist (Manuscript Service must be working).
Analysis publishes events that Translation will consume.
You must build the pipeline in the order data flows through it.

### What you're moving

From the SSP monolith, the Analysis Service takes ownership of:
- `AnalyzeChapterUseCase` and `AnalyzeChapterService`
- `CharacterExtractionResult`, `SceneAnalysisResult`
- All character, scene, relationship, world state JPA entities and adapters
- pgvector / `PgVectorEmbeddingAdapter`
- All `EmbeddingPort` search and embed methods

The Analysis Service has its own database (`analysis_db`) containing:
- Characters, CharacterStates, CharacterRelationships, RelationshipStates
- Scenes
- Glossary, StyleExamples
- ProjectWorldStates
- The pgvector `vector_store` table

### The key new concept: Kafka consumer

The Analysis Service does not have an API that you call to trigger analysis.
Instead, it listens. When `chapter.ingested` appears in Kafka, it wakes up and processes it.

This is a `@KafkaListener` annotated method. In the monolith, this was a `@EventListener`.
The concept is identical — the difference is that events are now durable (Kafka persists them),
distributed (other services can also consume them), and ordered per partition.

One important Kafka concept to understand here: **consumer groups**.
If you run two instances of Analysis Service, Kafka distributes the events between them automatically.
Each event is processed by exactly one instance. This is horizontal scaling for free.

### What the Analysis Service needs from Manuscript

The Kafka event payload contains the `chapterId`. The Analysis Service needs the full chapter text
to analyze. It gets this by making a **REST call back to the Manuscript Service**:
`GET /api/manuscripts/chapters/{chapterId}` → returns originalText.

This teaches you an important pattern: **events carry IDs, not full data**.
The ID lets the consumer fetch what it needs. Don't put the entire chapter text in the Kafka event —
events should be lightweight.

### After analysis completes

When all extraction is done, the Analysis Service:
1. Updates the chapter's analysis status to `ANALYZED`
2. Publishes `chapter.analyzed` to Kafka (Translation Service will consume this)

### The Subscription check

Before starting analysis, the Analysis Service calls the Subscription Service:
`GET /api/subscription/users/{userId}/can-analyze`

If the response is "no" (free tier limit hit), the chapter stays in `PENDING` state.
The author gets notified (Notification Service, Phase 8).

At Phase 3, the Subscription Service doesn't exist yet. Build a stub:
the Subscription Service always returns "yes" during development.
This is called a **test double** at the service level — it's acceptable during iterative development.

### What "done" looks like

- Upload a chapter through Gateway → it appears in Kafka `chapter.ingested`
- Analysis Service picks up the event → calls Manuscript REST API for chapter text
- AI pipeline runs → characters and scenes saved to `analysis_db`
- `chapter.analyzed` event appears in Kafka `chapter.analyzed` topic
- `GET /api/analysis/chapters/{id}` returns character names and scene count

### What you'll learn in this phase

- Kafka consumer setup (`@KafkaListener`, consumer groups)
- How services communicate: events for async trigger, REST for synchronous data fetch
- Why events carry IDs not full payloads
- Consumer group offset management (Kafka remembers where each consumer left off)
- Service stub pattern for not-yet-built dependencies

---

## Phase 4 — PageIndex Tree

### What you're doing

Adding the PageIndex hierarchical tree to the Analysis Service. The tree is built once after
all chapters of a project are analyzed. It is stored as a JSONB blob in `analysis_db`.

### Why this phase comes before Translation

The Translation Service upgrade (Phase 5) will call the Analysis Service's narrative-context
endpoint. That endpoint won't exist until Phase 4.

Also, PageIndex is an Analysis concern — it's built from the same scene data that the
analysis pipeline produces. It belongs in Analysis Service, not Translation.

### What you're building inside Analysis Service

The PageIndex builder is a new pipeline that runs after all chapters are `ANALYZED`:

1. For each scene already stored: generate a 2-sentence summary (LLM call)
2. Roll up scene summaries into a chapter summary (LLM call)
3. If manuscript is large enough: roll up chapters into arc summaries (LLM call)
4. Store the entire tree as a nested JSON structure in one table row

This is a one-time build per project, stored, and reused.

### The adaptive depth concept (why it matters)

If you build a 2-level tree (scenes → chapters → root) for a 300-chapter manuscript,
the root node would contain 300 chapter summaries. Sending all 300 to the LLM at query time
is expensive and slow.

The solution: add intermediate levels.
- ≤50 chapters → 2 levels (flat)
- 51–200 chapters → 3 levels (chapters grouped into arcs)
- 200+ chapters → 4 levels (arcs grouped into acts)

At query time, you only ever send 5–10 summaries to the LLM per hop.
The tree depth absorbs the scale, not the token count.

### The rebuild trigger

The tree must be rebuilt when new chapters are added. But rebuilding after every single
chapter is wasteful. The rule: rebuild when the chapter count increases by ≥10 since the
last build. This is a simple threshold check in the Analysis Service.

### The REST endpoint this phase exposes

`GET /api/analysis/projects/{id}/narrative-context?query=...`

The Coaching Service and the Translation Service (Phase 5) will call this.
The Analysis Service runs the tool-calling agent (Spring AI `@Tool` methods) that navigates
the tree and returns a relevant narrative summary.

### What "done" looks like

- All chapters of a test project analyzed → tree build triggered automatically
- `GET /api/analysis/projects/{id}/narrative-context?query=what+are+the+themes` →
  returns a coherent narrative summary
- The response references specific chapters and character arcs
- The same endpoint called twice with the same query hits the stored tree, not a rebuild

### What you'll learn in this phase

- JSONB storage in PostgreSQL (how to store and query nested JSON in Postgres)
- Spring AI `@Tool` annotations and agent loop
- Recursive data structures in Java (the `PageIndexNode` tree)
- Why adaptive tree depth matters for scalability
- On-demand vs pre-computed caching patterns

---

## Phase 5 — Translation Service

### What you're doing

Moving the two-pass literary translation pipeline from SSP into the Translation Service,
and upgrading its context assembly to include narrative context from Analysis Service.

### Why this phase comes here

Translation depends on analysis being complete (`chapter.analyzed` event). The Translation
Service consumes that event as its trigger. Analysis must exist first.

Also, the narrative context endpoint (Phase 4) must be ready before you can call it here.

### What you're moving

From the SSP monolith, the Translation Service takes ownership of:
- `TranslateChapterUseCase` and `TranslateChapterService`
- `ReviewTranslationUseCase` and `ReviewTranslationService`
- `ChapterTranslation` domain model and all its JPA infrastructure
- The two-pass translation prompt engineering

### What changes in the translation pipeline

The existing context assembly (character RAG, glossary RAG, style example RAG) stays identical.
You add a fourth context call:

Instead of just building WHO/WHAT/HOW context, you now also build WHERE/WHEN context:
call `GET /api/analysis/projects/{id}/narrative-context?query=what+has+happened+up+to+this+point`
from Analysis Service.

This gives the translator: "the kingdom just fell, the protagonist betrayed their mentor,
and the tone is foreboding heading into this chapter" — without the LLM having to guess
from character names alone.

### The key new concept: service-to-service REST clients

The Translation Service calls the Analysis Service. In Spring Boot you do this with:
- `RestClient` (Spring 6 synchronous client — recommended)
- Or `WebClient` (reactive)

You create a `NarrativeContextClient` class in Translation Service that wraps the REST call.
This client is an outbound port adapter — just like how `OpenAiAdapter` wraps the AI API call.

The same hexagonal principle: `TranslateChapterService` calls a `NarrativeContextPort`.
The implementation (`NarrativeContextClient`) makes the REST call.
If tomorrow you move narrative context to a different service, only the adapter changes.

### What "done" looks like

- Upload chapter → analysis completes → `chapter.analyzed` event triggers translation
- Translation context assembly includes character RAG + glossary RAG + style RAG + narrative context
- The narrative context in the prompt references things that actually happened in prior chapters
- `GET /api/translation/chapters/{id}/text` returns the full two-pass translated text
- The translation sounds coherent with the rest of the story

### What you'll learn in this phase

- `@KafkaListener` triggering async business logic
- `RestClient` for synchronous service-to-service calls
- Applying the port abstraction to external service calls (not just databases)
- How context assembly improves with richer narrative data

---

## Phase 6 — Subscription and Billing

### What you're doing

Building the Subscription Service that enforces tier limits and integrates with Stripe for payments.
Replacing the stub from Phase 3 with the real implementation.

### Why this phase comes after Translation

You built all the product features first. Now you monetize them.
This order is deliberate: you want to know the product works before building the gate.

Also, testing Phases 3–5 is easier without limits enforced. Once Subscription is real, you'll
need to manage tier state in tests.

### What the Subscription Service owns

Its database (`subscription_db`) stores:
- User subscription plans (userId, tier, status, stripeCustomerId)
- Usage counters (chaptersAnalyzed this month, translationsCompleted)
- Plan limits table (FREE: 10 chapters/month, BASIC: 50, PRO: 150, UNLIMITED: no limit)

### The can-analyze check

Analysis Service calls `GET /api/subscription/users/{userId}/can-analyze` before starting.
The Subscription Service checks: has this user hit their monthly analysis limit?

If yes → return 403. Analysis Service does not start the pipeline.
If no → return 200. Analysis Service proceeds.

This check is synchronous (REST) because it's a gate — the analysis must not start until
permission is confirmed.

### Stripe integration

Stripe is the payment processor. You integrate two things:

**Checkout session:** When a user upgrades their plan, your Coaching Service (or a settings API)
calls Stripe to create a checkout session and redirects the user to Stripe's payment page.

**Webhook:** After payment succeeds, Stripe calls your Subscription Service at
`POST /api/subscription/stripe/webhook`. Your service verifies the webhook signature (Stripe
provides this — it's how you know the call is legitimate, not spoofed), then upgrades the user's
plan in your database.

This is asynchronous — Stripe calls you. You don't call Stripe and wait.

### Kafka consumers

The Subscription Service also listens to Kafka events to track usage:
- `chapter.analyzed` → increment user's monthly analysis count
- `translation.completed` → increment translation count

This is the beauty of Kafka: the Analysis Service never tells Subscription "hey, count this".
The Subscription Service just observes the event stream and keeps its own counters.

### What "done" looks like

- Free tier user analyzes 10 chapters → 11th triggers `can-analyze` returning 403
- User pays via Stripe → webhook upgrades their plan to BASIC
- 11th chapter analysis now succeeds
- Usage counter resets on the 1st of each month (simple scheduled task)

### What you'll learn in this phase

- Stripe SDK integration (webhook verification, checkout session creation)
- Event-driven usage tracking (Kafka consumer as an observer, not a direct call)
- Synchronous gates (REST) vs asynchronous reactions (Kafka)
- Monthly usage reset with Spring `@Scheduled`

---

## Phase 7 — Coaching Service

### What you're doing

Building the Coaching Service — the premium product. This is the monetizable feature that
justifies the entire platform.

### Why this comes second-to-last

The Coaching Service calls three other services:
- Analysis Service (for PageIndex tree navigation and character/scene data)
- Subscription Service (to verify the user has a coaching-enabled tier)
- It reads events from Kafka to know when new analysis data is available

All three must exist before you build coaching.

### What the Coaching Service does

It answers two categories of questions about a manuscript:

**1. Narrative Health Report** — a structured document generated on demand.
The author clicks "Generate Report" and gets:
- Overall manuscript summary
- Theme analysis
- Pacing analysis (which arcs are rushed, which drag)
- Character arc consistency (does each character's journey resolve?)
- Relationship evolution (how do key relationships change?)
- Underutilized characters
- A 0–100 narrative health score

**2. Interactive Q&A** — the author types a question, the system answers with manuscript-specific context.
- "Does my protagonist's arc feel complete?"
- "What's the central conflict and does it resolve?"
- "Which chapters feel the slowest?"

### The QueryRouter

Not every question is answered the same way. Some need the full manuscript overview (PageIndex global),
some need specific chapter comparisons (PageIndex targeted), some need character history (Vector RAG).

The QueryRouter classifies the question and picks the right retrieval strategy:
- Global question ("what are the themes") → PageIndex from root
- Specific character question ("how has Elena changed") → Vector RAG on CharacterState
- Comparison question ("does chapter 3 contradict chapter 15") → PageIndex targeted retrieval for those two chapters

The QueryRouter is a simple LLM call itself: "classify this question — global/character/comparison/other"
→ routes to the right tool.

### The Spring AI tool-calling agent

Once the routing decision is made, the actual answer generation uses the `@Tool` methods
on PageIndex navigation. The LLM calls `listArcs()`, `getChapterSummaries()`, etc.
until it has enough context to answer.

This is where MCP-style tool calling comes in (as discussed earlier). Spring AI handles it natively.

### What "done" looks like

- `POST /api/coaching/projects/{id}/reports/generate` returns a JSON report with all sections
- Each section has actual manuscript-specific content (not generic advice)
- The narrative health score changes when a chapter with poor pacing is added
- Interactive Q&A about a specific character references that character's actual arc

### What you'll learn in this phase

- LLM-as-router pattern (classify before processing)
- Spring AI tool calling / agentic loops in production
- Composite reporting (combining multiple data sources into one document)
- Scoring algorithms (weighted composite scores)
- Multi-call AI pipelines with controlled cost

---

## Phase 8 — Notifications

### What you're doing

Building the Notification Service that sends emails and real-time dashboard updates
when things happen in the system.

### Why this comes last

Notifications are purely reactive — they listen to events and push messages.
They depend on all events existing (chapter.analyzed, translation.completed, etc.).
Nothing in the core pipeline depends on notifications.
Building notifications last means you never block on them, and they can be added without
touching any other service.

### What it does

The Notification Service subscribes to Kafka events:
- `chapter.analyzed` → email "Your analysis is ready"
- `translation.completed` → email "Your translation is ready"
- `page.index.ready` → email "Coaching report ready to generate"
- `subscription.payment.failed` → email "Payment issue — please update billing"
- `auth.password-reset-requested` → email with reset link

It also pushes real-time updates to the frontend via WebSocket:
- When translation completes, the dashboard status badge updates without page refresh.

### Email provider

Use SendGrid or AWS SES. Both have Spring Boot starters. The service just calls their API
with the recipient, subject, and body. Keep it simple — no custom email server.

### WebSocket

Spring Boot has native WebSocket support. The frontend connects to
`ws://gateway:8080/ws/notifications`. When a relevant event happens, the server pushes
a notification payload to connected clients for that user.

The Gateway must be configured to pass WebSocket connections through (it supports this).

### What "done" looks like

- Analyze a chapter → email arrives within 30 seconds
- Translation completes → dashboard badge updates in real-time without refresh
- Failed Stripe payment → email with update link sent immediately

### What you'll learn in this phase

- WebSocket with Spring Boot (STOMP over WebSocket)
- Email API integration (SendGrid or SES)
- The observer pattern at scale — notifications never need to know about business logic
- Why keeping notification logic in its own service means you can add/change notifications
  without touching any business service

---

## The Bigger Picture — What You'll Own After All 8 Phases

After completing all phases, you'll have hands-on experience with:

| Concept | Where you learned it |
|---|---|
| Maven multi-module projects | Phase 0 |
| Docker Compose for local infrastructure | Phase 0 |
| JWT authentication flow | Phase 1 |
| API Gateway and reverse proxy routing | Phase 1 |
| Hexagonal architecture across multiple services | Every phase |
| Kafka producer (publishing events) | Phase 2 |
| Kafka consumer (reacting to events) | Phases 3, 6, 8 |
| Service-to-service REST calls | Phases 3, 4, 5 |
| JSONB storage in PostgreSQL | Phase 4 |
| Spring AI tool calling / agentic loops | Phase 4, 7 |
| Stripe payment integration + webhooks | Phase 6 |
| Usage metering and tier enforcement | Phase 6 |
| LLM-as-router pattern | Phase 7 |
| WebSocket real-time push | Phase 8 |
| Event-driven architecture end to end | All phases |

The order is deliberate. Infrastructure before features. Identity before data.
Upstream services before downstream. Core pipeline before monetization.
Core pipeline before notifications.

Every service you build teaches you something different about how distributed systems work.
By the end, you won't just have a working platform — you'll understand *why* each architectural
decision was made, which is what it means to intellectually own a system.

---

## How to approach each phase day-to-day

1. **Read the phase section in `microservice-platform-plan.md`** for the detailed technical plan
   (DB schemas, Kafka topics, exact endpoints, code structure).

2. **Read the corresponding section in this guide** for the conceptual understanding.

3. **Build it.** Don't try to make it perfect on the first pass. Make the "done when" checkpoint.

4. **Test the checkpoint manually** (Postman or IntelliJ's HTTP client) before moving to the next phase.

5. **Don't start the next phase until the current one's checkpoint is clean.**

This prevents the common trap of building half of 4 phases and having nothing fully working.
A fully working Phase 2 is worth more than a broken Phase 5.

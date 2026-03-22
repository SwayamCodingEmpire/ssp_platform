# SSP Platform — Full Microservice Architecture & Implementation Plan
# Hybrid Vector RAG + PageIndex | Author Coaching SaaS

> **Date:** 2026-03-21
> **Status:** Planning phase
> **Stack:** Spring Boot 4.0.3, Java 25, Spring Cloud, Kafka, PostgreSQL, pgvector, Spring AI 2.0.0-M2

---

## Table of Contents

1. [Big Picture](#1-big-picture)
2. [Service Inventory](#2-service-inventory)
3. [Each Service — Full Detail](#3-each-service--full-detail)
   - [3.1 API Gateway](#31-api-gateway)
   - [3.2 Auth Service](#32-auth-service)
   - [3.3 Subscription Service](#33-subscription-service)
   - [3.4 Manuscript Service](#34-manuscript-service)
   - [3.5 Analysis Service](#35-analysis-service)
   - [3.6 Translation Service](#36-translation-service)
   - [3.7 Coaching Service](#37-coaching-service)
   - [3.8 Notification Service](#38-notification-service)
4. [Kafka Topic Design](#4-kafka-topic-design)
5. [Database Per Service Summary](#5-database-per-service-summary)
6. [New Files Required Per Service](#6-new-files-required-per-service)
7. [Local Development Setup](#7-local-development-setup-intellij-ultimate)
8. [Build Phases](#8-build-phases)
9. [Hybrid RAG + PageIndex — Why and How](#9-hybrid-rag--pageindex--why-and-how)
10. [Cost Impact — Author Coaching SaaS](#10-cost-impact--author-coaching-saas-corrected)
11. [Summary](#11-summary-view)
12. [Series-Aware Domain Model](#12-series-aware-domain-model)
o13. [Role-Based Access Control](#13-role-based-access-control-rbac)

---

## 1. Big Picture

```
                        ┌─────────────────────────────┐
                        │         API Gateway          │
                        │    (Spring Cloud Gateway)    │
                        │  • JWT validation            │
                        │  • Rate limiting per tier    │
                        │  • Route to services         │
                        └──────────────┬───────────────┘
                                       │
       ┌──────────────┬────────────────┼──────────────┬──────────────┐
       │              │                │              │              │
       ▼              ▼                ▼              ▼              ▼
  Auth Service   Subscription     Manuscript      Analysis       Translation
   (port 8081)   Service          Service         Service        Service
                 (port 8082)      (port 8083)     (port 8084)    (port 8085)
                                       │               │
                                       │               │
                                       ▼               ▼
                                ┌─────────────────────────┐
                                │          Kafka           │
                                │  • ChapterIngested       │
                                │  • ChapterAnalyzed       │
                                │  • PageIndexReady        │
                                │  • TranslationRequested  │
                                │  • TranslationCompleted  │
                                └──────────┬──────────────┘
                                           │
                      ┌────────────────────┼────────────────────┐
                      │                    │                     │
                      ▼                    ▼                     ▼
               Coaching Service    Notification Service    (future services)
                (port 8086)          (port 8087)
```

---

## 2. Service Inventory

| Service | Owns | DB | Communicates via |
|---|---|---|---|
| **API Gateway** | Routing, auth filter, rate limits | None | REST (inbound/outbound) |
| **Auth Service** | Users, JWT, sessions | `auth_db` | REST |
| **Subscription Service** | Plans, billing, usage limits | `subscription_db` | REST + Kafka |
| **Manuscript Service** | Projects, chapters (raw text) | `manuscript_db` | REST + Kafka |
| **Analysis Service** | Characters, scenes, relationships, embeddings, PageIndex tree | `analysis_db` + pgvector | Kafka consumer + REST |
| **Translation Service** | Translations, review workflow | `translation_db` | Kafka consumer + REST |
| **Coaching Service** | Coaching reports, narrative health scores | `coaching_db` | Kafka consumer + REST |
| **Notification Service** | Email / WebSocket push | None (stateless) | Kafka consumer |

---

## 3. Each Service — Full Detail

---

### 3.1 API Gateway

**Tech:** Spring Cloud Gateway

**Responsibilities:**
- Single entry point for all client traffic
- Validates JWT on every request (except `/auth/**`)
- Injects `X-User-Id` and `X-User-Tier` headers downstream so services don't re-validate tokens
- Per-tier rate limiting (Free: 10 req/min, Basic: 60, Pro: 300)
- Request logging and distributed tracing headers (`X-Trace-Id`)

**Route configuration:**
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: auth
          uri: http://auth-service:8081
          predicates:
            - Path=/auth/**
          filters:
            - StripPrefix=0       # no JWT check on auth routes

        - id: manuscript
          uri: http://manuscript-service:8083
          predicates:
            - Path=/api/v2/projects/**, /api/v2/chapters/**
          filters:
            - AuthFilter          # validates JWT, injects headers

        - id: analysis
          uri: http://analysis-service:8084
          predicates:
            - Path=/api/v2/analysis/**, /api/v2/projects/*/characters/**

        - id: translation
          uri: http://translation-service:8085
          predicates:
            - Path=/api/v2/translation/**

        - id: coaching
          uri: http://coaching-service:8086
          predicates:
            - Path=/api/v2/coaching/**
```

**JWT Filter flow:**
```
Request arrives
    ↓
Extract Bearer token from Authorization header
    ↓
Validate signature + expiry (using shared secret with Auth Service)
    ↓
Extract userId, email, tier from claims
    ↓
Set X-User-Id, X-User-Tier headers
    ↓
Forward to downstream service
```

**Dependencies:**
```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

---

### 3.2 Auth Service

**Tech:** Spring Boot + Spring Security + JWT + PostgreSQL

**Port:** 8081

**Responsibilities:**
- User registration and login
- JWT issuance (access token: 1h, refresh token: 30d)
- Token refresh
- User profile (name, email, avatar)
- Password reset flow (publishes event to Notification Service)

**Database schema:**
```sql
-- auth_db

CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255) UNIQUE NOT NULL,
    password        VARCHAR(255) NOT NULL,       -- bcrypt
    name            VARCHAR(255),
    avatar_url      VARCHAR(500),
    email_verified  BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP
);

CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id),
    token_hash  VARCHAR(255) NOT NULL,           -- SHA-256 of actual token
    expires_at  TIMESTAMP NOT NULL,
    revoked     BOOLEAN DEFAULT FALSE,
    created_at  TIMESTAMP NOT NULL
);
```

**JWT payload (injected into every downstream request via gateway headers):**
```json
{
  "sub": "42",
  "email": "author@example.com",
  "tier": "PRO",
  "iat": 1711000000,
  "exp": 1711003600
}
```

**REST Endpoints:**
```
POST /auth/register           { email, password, name }
POST /auth/login              → { accessToken, refreshToken, user }
POST /auth/refresh            { refreshToken } → new accessToken
POST /auth/logout             revokes refresh token
GET  /auth/me                 current user profile (requires JWT)
PUT  /auth/me                 update profile
POST /auth/forgot-password    triggers email via Kafka
POST /auth/reset-password     { token, newPassword }
```

**Key Dependencies:**
```xml
spring-boot-starter-security
spring-boot-starter-data-jpa
jjwt-api + jjwt-impl + jjwt-jackson
spring-kafka
postgresql
```

---

### 3.3 Subscription Service

**Tech:** Spring Boot + Stripe SDK + Kafka + PostgreSQL

**Port:** 8082

**Responsibilities:**
- Manage subscription plans (FREE, BASIC, PRO, UNLIMITED)
- Stripe checkout session creation and webhook handling
- **Chapter limit enforcement** — gating point before analysis starts
- Usage tracking (chapters analyzed/translated per user per month)
- Overage detection

**Database schema:**
```sql
-- subscription_db

CREATE TABLE subscriptions (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL UNIQUE,
    plan                    VARCHAR(20) NOT NULL,   -- FREE, BASIC, PRO, UNLIMITED
    stripe_customer_id      VARCHAR(255),
    stripe_sub_id           VARCHAR(255),
    status                  VARCHAR(20) NOT NULL,   -- ACTIVE, CANCELLED, PAST_DUE
    current_period_start    TIMESTAMP,
    current_period_end      TIMESTAMP,
    created_at              TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP
);

CREATE TABLE usage_events (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    event_type      VARCHAR(50) NOT NULL,   -- CHAPTER_ANALYZED, CHAPTER_TRANSLATED
    project_id      BIGINT NOT NULL,
    chapter_id      BIGINT NOT NULL,
    period_month    VARCHAR(7) NOT NULL,    -- "2026-03" monthly bucket
    created_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_usage_user_period ON usage_events(user_id, period_month);

CREATE TABLE plan_limits (
    plan                        VARCHAR(20) PRIMARY KEY,
    analyses_per_month          INTEGER,   -- NULL = unlimited
    max_chapters_per_analysis   INTEGER,   -- NULL = unlimited
    translations_per_month      INTEGER,
    price_usd_cents             INTEGER
);

INSERT INTO plan_limits VALUES
    ('FREE',      1,   10,  1,    0),
    ('BASIC',     3,   50,  3,    1000),
    ('PRO',       10,  150, 10,   3000),
    ('UNLIMITED', NULL, NULL, NULL, 10000);
```

**Chapter limit enforcement flow (synchronous REST, called by Analysis Service):**
```
Analysis Service → GET /subscriptions/users/{userId}/can-analyze?chapters=50
Subscription Service checks:
  - Active subscription?
  - Monthly analysis allowance remaining?
  - Chapter count within per-analysis limit?
→ { allowed: true/false, reason: "LIMIT_EXCEEDED" }
```

**Stripe webhook events handled:**
```
checkout.session.completed      → activate subscription
customer.subscription.updated   → plan change
customer.subscription.deleted   → cancel
invoice.payment_failed          → mark PAST_DUE
```

**REST Endpoints:**
```
GET  /subscriptions/users/{userId}/plan
GET  /subscriptions/users/{userId}/usage
GET  /subscriptions/users/{userId}/can-analyze?chapters=N
POST /subscriptions/checkout                    → create Stripe checkout session
POST /subscriptions/webhook/stripe              → Stripe webhook
GET  /subscriptions/plans                       → list all plans with limits/prices
```

---

### 3.4 Manuscript Service

**Tech:** Spring Boot + Apache Tika + Kafka + PostgreSQL

**Port:** 8083

**Responsibilities:**
- Project CRUD
- Chapter ingestion from text, JSON body, or file upload (PDF/EPUB/DOCX/TXT)
- File parsing via Apache Tika (extracted from current SSP)
- Content-hash deduplication (SHA-256, already implemented)
- Publish `ChapterIngestedEvent` to Kafka

**Database schema:**
```sql
-- manuscript_db

CREATE TABLE projects (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    title           VARCHAR(500) NOT NULL,
    source_language VARCHAR(10),
    status          VARCHAR(30),
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP
);

CREATE TABLE chapters (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES projects(id),
    chapter_number  INTEGER NOT NULL,
    title           VARCHAR(500),
    original_text   TEXT,
    content_hash    VARCHAR(64),           -- SHA-256, dedup key
    status          VARCHAR(30),
    analysis_status VARCHAR(30),
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    UNIQUE(project_id, chapter_number, content_hash)
);
```

**Kafka event published:**
```json
// Topic: chapter.ingested
{
  "eventId": "uuid-v4",
  "eventType": "chapter.ingested",
  "occurredAt": "2026-03-21T10:00:00Z",
  "userId": 99,
  "projectId": 7,
  "payload": {
    "chapterId": 42,
    "chapterNumber": 3,
    "analysisEnabled": true
  }
}
```

**REST Endpoints:**
```
POST   /api/v2/projects
GET    /api/v2/projects
GET    /api/v2/projects/{id}
PUT    /api/v2/projects/{id}
DELETE /api/v2/projects/{id}

POST   /api/v2/chapters           JSON body
POST   /api/v2/chapters/text      plain text
POST   /api/v2/chapters/upload    multipart file (max 50MB)
GET    /api/v2/chapters/{id}
GET    /api/v2/projects/{id}/chapters
```

---

### 3.5 Analysis Service

**Tech:** Spring Boot + Spring AI + pgvector + Kafka + PostgreSQL

**Port:** 8084

**This is the current SSP core** — character extraction, scene analysis, world state, vector embeddings — plus the new **PageIndex tree builder**.

**Database schema:**
```sql
-- analysis_db (PostgreSQL + pgvector extension)

-- Existing SSP tables (migrated as-is)
CREATE TABLE characters ( ... );
CREATE TABLE character_states ( ... );
CREATE TABLE character_relationships ( ... );
CREATE TABLE relationship_states ( ... );
CREATE TABLE scenes ( ... );
CREATE TABLE glossary ( ... );
CREATE TABLE style_examples ( ... );
CREATE TABLE project_world_states ( ... );

-- pgvector table (managed by Spring AI auto-config)
CREATE TABLE vector_store (
    id          UUID PRIMARY KEY,
    content     TEXT,
    metadata    JSONB,
    embedding   VECTOR(1536)
);
CREATE INDEX ON vector_store USING hnsw (embedding vector_cosine_ops);

-- NEW: PageIndex tree storage
CREATE TABLE project_page_indexes (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL UNIQUE,
    tree_json       JSONB NOT NULL,        -- full serialized PageIndexNode tree
    chapter_count   INTEGER NOT NULL,      -- detect when rebuild is needed
    built_at        TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP
);
```

**Kafka events consumed:**
```
chapter.ingested → triggers full analysis pipeline
```

**Kafka events published:**
```
chapter.analyzed  → triggers PageIndex rebuild check, usage tracking, notification
page.index.ready  → signals Translation and Coaching services tree is available
```

**Existing analysis pipeline (unchanged):**
```
ChapterIngestedEvent consumed
          ↓
Call Subscription Service: can this user analyze?
          ↓
chapter.startAnalysis()
          ↓
Parallel:
  ├── extractCharacters()     LLM call #1 (~17,000 tokens)
  └── analyzeScenes()         LLM call #2 (~14,500 tokens)
          ↓
saveCharacters(), saveScenes(), saveRelationships()
          ↓
Parallel async (fire-and-forget):
  ├── embedCharacters()       pgvector upsert
  ├── embedScenes()           pgvector upsert
  └── updateWorldState()      LLM call #3 (~1,500 tokens)
          ↓
chapter.completeAnalysis()
          ↓
publish ChapterAnalyzedEvent
```

**NEW: PageIndex Build Pipeline**

Triggered when `ChapterAnalyzedEvent` arrives and all chapters of a project are `ANALYZED`:

```
All chapters analyzed for project P
          ↓
PageIndexBuilderService.buildTree(projectId)
          ↓
Step 1: Load all scenes from DB (already analyzed)
          ↓
Step 2: Build leaf nodes (one per scene)
  LLM: "Summarize this scene's narrative significance in 2 sentences"
  → PageIndexNode { sceneId, title, summary, startChapter, endChapter, [] }
          ↓
Step 3: Build chapter nodes (group leaves by chapter)
  LLM: "Summarize this chapter's arc from these scene summaries"
  → PageIndexNode { chapterId, title, summary, N, N, [leafNodes] }
          ↓
Step 4: Build root node (manuscript level)
  LLM: "Summarize this manuscript's overall arc from all chapter summaries"
  → PageIndexNode { "root", projectTitle, rootSummary, 1, lastChapter, [chapterNodes] }
          ↓
Step 5: Serialize tree to JSON, store in project_page_indexes
          ↓
publish PageIndexReadyEvent
```

**NEW: PageIndex Retrieval** (called by Translation and Coaching services via REST)

```
GET /api/v2/analysis/projects/{id}/narrative-context
    ?query=what+has+happened+so+far
    &upToChapter=10
          ↓
Load tree from project_page_indexes
          ↓
Filter: only chapters 1..upToChapter (never leak future chapters into translation)
          ↓
Tree traversal — 3 LLM hops:
  Level 1 (root):     LLM decides which chapter nodes are relevant to query
  Level 2 (chapters): LLM decides which scene nodes are relevant
  Level 3 (scenes):   Return leaf summaries as context
          ↓
Return: 2-4 paragraph narrative context string
```

**REST Endpoints (existing + new):**
```
-- Existing (from SSP)
POST /api/v2/analysis/chapters/{id}
GET  /api/v2/analysis/chapters/{id}
GET  /api/v2/projects/{id}/characters
GET  /api/v2/projects/{id}/characters/{cid}/states
GET  /api/v2/projects/{id}/relationships
GET  /api/v2/projects/{id}/relationships/{rid}/history
GET  /api/v2/projects/{id}/scenes

-- NEW: PageIndex
GET  /api/v2/analysis/projects/{id}/narrative-context?query=...&upToChapter=N
GET  /api/v2/analysis/projects/{id}/page-index/status
POST /api/v2/analysis/projects/{id}/page-index/rebuild
```

---

### 3.6 Translation Service

**Tech:** Spring Boot + Spring AI + Kafka + PostgreSQL

**Port:** 8085

**This is the current SSP translation pipeline** — two-pass literary translation — upgraded to use **both Vector RAG and PageIndex** for richer context.

**Database schema:**
```sql
-- translation_db

CREATE TABLE chapter_translations (
    id                  BIGSERIAL PRIMARY KEY,
    chapter_id          BIGINT NOT NULL,
    project_id          BIGINT NOT NULL,
    target_language     VARCHAR(10) NOT NULL,
    translation_status  VARCHAR(30) NOT NULL,
    translated_text     TEXT,
    user_edited_text    TEXT,
    user_accepted       BOOLEAN,
    reviewed_at         TIMESTAMP,
    chunked             BOOLEAN DEFAULT FALSE,
    total_segments      INTEGER,
    provider_used       VARCHAR(50),
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP,
    UNIQUE(chapter_id, target_language)
);
```

**Upgraded context assembly — 4 parallel calls:**
```java
// Existing 3 — Vector RAG (unchanged):
CompletableFuture<String> characterBlock   = buildCharacterBlock(projectId, chapterText);
CompletableFuture<String> glossaryBlock    = buildGlossaryBlock(projectId, targetLang, chapterText);
CompletableFuture<String> styleBlock       = buildStyleExampleBlock(projectId, sceneType, targetLang);

// NEW 4th — PageIndex narrative context:
CompletableFuture<String> narrativeContext = fetchNarrativeContext(projectId, chapterNumber);
// REST call: GET /api/v2/analysis/projects/{id}/narrative-context
//            ?query=what+has+happened+in+this+story+so+far
//            &upToChapter={chapterNumber-1}

CompletableFuture.allOf(characterBlock, glossaryBlock, styleBlock, narrativeContext).join();
```

**What each context block provides to the translation LLM:**

| Block | Source | Provides |
|---|---|---|
| Character block | Vector RAG | WHO — character descriptions, aliases, traits |
| Glossary block | Vector RAG | WHAT — term translations, world-specific vocabulary |
| Style block | Vector RAG | HOW — per-scene-type prose examples |
| Narrative context | PageIndex | WHERE — arc position, what has happened, emotional trajectory |

**The narrative context added to Pass 1 prompt:**
```
=== NARRATIVE CONTEXT (story arc up to this chapter) ===
{narrativeContext from PageIndex traversal}

Use this context to:
- Maintain consistency with past events referenced in this chapter
- Preserve emotional continuity from prior character arcs
- Understand character motivations built over previous chapters
```

**REST Endpoints (unchanged from SSP):**
```
POST /api/v2/translation/chapters/{id}
GET  /api/v2/translation/chapters/{id}
GET  /api/v2/translation/chapters/{id}/text
GET  /api/v2/translation/chapters/{id}/languages
PUT  /api/v2/translation/chapters/{id}/save
```

---

### 3.7 Coaching Service

**Tech:** Spring Boot + Spring AI + Kafka + PostgreSQL

**Port:** 8086

**This is the new Author Coaching SaaS product** — the monetizable feature built on top of all analysis data. Uses PageIndex for global narrative questions and Vector RAG for entity-level questions.

**Database schema:**
```sql
-- coaching_db

CREATE TABLE coaching_reports (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    report_type     VARCHAR(50) NOT NULL,   -- FULL, PACING, CHARACTER_ARC, THEMES
    report_json     JSONB NOT NULL,         -- structured report with sections
    health_score    INTEGER,               -- 0-100 overall score
    generated_at    TIMESTAMP NOT NULL
);

CREATE TABLE author_queries (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    query           TEXT NOT NULL,
    answer          TEXT,
    retrieval_mode  VARCHAR(20),            -- PAGEINDEX, VECTOR_RAG, HYBRID
    answered_at     TIMESTAMP
);
```

**Narrative Health Report — sections and how each is generated:**

| Section | Generation method | RAG type |
|---|---|---|
| Overall manuscript summary | LLM over root PageIndex node | PageIndex |
| Theme analysis | LLM over chapter summaries | PageIndex global |
| Pacing analysis | Scene pace distribution + arc flow | Vector RAG + PageIndex |
| Character arc consistency | CharacterState history per character | Vector RAG |
| Relationship evolution | RelationshipState history | Vector RAG |
| Underutilized characters | Graph degree analysis (low edge count) | Direct DB query to Analysis Service |
| Tonal inconsistencies | Scene EmotionalTone distribution | Vector RAG |
| World-building gaps | Glossary coverage vs chapter count | Direct DB query |
| Health score (0–100) | Weighted composite of above sections | Computed |

**Author Q&A — hybrid query routing:**
```
Author asks: "Is Aria's motivation consistent throughout the story?"
    ↓ QueryRouter → CHARACTER_ARC → Vector RAG (CharacterState history)

Author asks: "What are the main themes of my novel?"
    ↓ QueryRouter → GLOBAL → PageIndex traversal from root

Author asks: "Does chapter 15 contradict what happened in chapter 3?"
    ↓ QueryRouter → SPECIFIC_CHAPTERS → PageIndex targeted retrieval (chapters 3 and 15)

Author asks: "Who are the most important characters?"
    ↓ QueryRouter → ENTITY → Vector RAG + relationship graph degree
```

**REST Endpoints:**
```
POST /api/v2/coaching/projects/{id}/reports/generate
GET  /api/v2/coaching/projects/{id}/reports
GET  /api/v2/coaching/reports/{reportId}

POST /api/v2/coaching/projects/{id}/ask     { "query": "..." }
GET  /api/v2/coaching/projects/{id}/queries

GET  /api/v2/coaching/projects/{id}/health  → quick health score (0-100)
```

---

### 3.8 Notification Service

**Tech:** Spring Boot + Kafka consumer + SendGrid/AWS SES

**Port:** 8087

**Stateless** — no database. Consumes events and sends emails or WebSocket pushes.

**Kafka events consumed and actions:**

| Event | Email sent | WebSocket push |
|---|---|---|
| `chapter.analyzed` | "Your chapter analysis is ready" | `{ type: ANALYSIS_COMPLETE, chapterId }` |
| `page.index.ready` | "Coaching features now available for your project" | `{ type: INDEX_READY, projectId }` |
| `translation.completed` | "Translation complete" | `{ type: TRANSLATION_COMPLETE, chapterId, language }` |
| `auth.password-reset` | Password reset link | — |
| `subscription.payment-failed` | Payment failure warning | — |
| `subscription.changed` | Plan upgrade/downgrade confirmation | — |

---

## 4. Kafka Topic Design

```
Topics and ownership:

chapter.ingested
  Producer:  Manuscript Service
  Consumers: Analysis Service

chapter.analyzed
  Producer:  Analysis Service
  Consumers: Subscription Service  (increment usage count)
             Notification Service  (email + WS push)
             Coaching Service      (invalidate stale reports)

page.index.ready
  Producer:  Analysis Service
  Consumers: Translation Service   (narrative context now available)
             Coaching Service      (coaching now possible)
             Notification Service  (email)

translation.completed
  Producer:  Translation Service
  Consumers: Notification Service
             Subscription Service  (increment usage count)

auth.password-reset
  Producer:  Auth Service
  Consumer:  Notification Service

subscription.changed
  Producer:  Subscription Service
  Consumer:  API Gateway           (rate limit cache refresh)
```

**Standard event envelope (all topics):**
```json
{
  "eventId": "uuid-v4",
  "eventType": "chapter.analyzed",
  "occurredAt": "2026-03-21T10:00:00Z",
  "userId": 42,
  "projectId": 7,
  "payload": {
    "chapterId": 15,
    "chapterNumber": 3,
    "charactersFound": 4,
    "scenesDetected": 2
  }
}
```

---

## 5. Database Per Service Summary

| Service | DB Name | Notable tables |
|---|---|---|
| Auth | `auth_db` | users, refresh_tokens |
| Subscription | `subscription_db` | subscriptions, usage_events, plan_limits |
| Manuscript | `manuscript_db` | projects, chapters (with content_hash) |
| Analysis | `analysis_db` + pgvector | characters, character_states, relationships, relationship_states, scenes, glossary, style_examples, world_states, **project_page_indexes**, vector_store |
| Translation | `translation_db` | chapter_translations |
| Coaching | `coaching_db` | coaching_reports, author_queries |

**Rule:** Each service connects **only to its own DB**. No cross-DB joins ever. Cross-service data access = REST call or Kafka event.

---

## 6. New Files Required Per Service

### Analysis Service — PageIndex additions

```
domain/model/narrative/
  └── PageIndexNode.java
        record { nodeId, title, summary, startChapter, endChapter, List<PageIndexNode> children }

domain/port/in/
  └── BuildPageIndexUseCase.java
        build(projectId), retrieveNarrativeContext(projectId, query, upToChapter)

domain/port/out/persistence/
  └── PageIndexPersistencePort.java
        save(projectId, tree), findByProjectId(id), existsByProjectId(id)

application/
  └── PageIndexService.java
        buildTree(), traverseTree(), retrieveNarrativeContext()
  └── PageIndexPrompts.java
        LEAF_SUMMARY_PROMPT, CHAPTER_ROLLUP_PROMPT,
        MANUSCRIPT_ROOT_PROMPT, TRAVERSAL_PROMPT

adapter/in/web/
  └── PageIndexController.java
        GET /narrative-context, GET /status, POST /rebuild

adapter/out/persistence/entity/
  └── ProjectPageIndexJpaEntity.java
        projectId, treeJson (JSONB), chapterCount, builtAt

adapter/out/persistence/repository/
  └── SpringDataPageIndexRepository.java

adapter/out/persistence/
  └── JpaPageIndexAdapter.java
```

### Translation Service — context upgrade

```
application/
  └── TranslateChapterService.java   add 4th parallel call: fetchNarrativeContext()
  └── NarrativeContextClient.java    REST client → Analysis Service narrative-context endpoint
```

### Coaching Service — entirely new service

```
domain/model/
  └── CoachingReport.java
  └── AuthorQuery.java
  └── NarrativeHealthScore.java
  └── ReportSection.java

domain/port/in/
  └── GenerateCoachingReportUseCase.java
  └── AskAuthorQueryUseCase.java

application/
  └── CoachingReportService.java     orchestrates report generation
  └── AuthorQueryService.java        handles author Q&A
  └── QueryRouter.java               decides PAGEINDEX vs VECTOR_RAG vs HYBRID per query

adapter/in/web/
  └── CoachingController.java

adapter/out/
  └── AnalysisServiceClient.java     REST calls → Analysis Service (narrative context + RAG)
```

---

## 7. Local Development Setup (IntelliJ Ultimate)

### Maven multi-module project structure

```
ssp-platform/
├── pom.xml                     ← parent POM, manages versions only, no code
├── api-gateway/
│   └── pom.xml
├── auth-service/
│   └── pom.xml
├── subscription-service/
│   └── pom.xml
├── manuscript-service/
│   └── pom.xml
├── analysis-service/           ← current SSP codebase moved here
│   └── pom.xml
├── translation-service/
│   └── pom.xml
├── coaching-service/
│   └── pom.xml
├── notification-service/
│   └── pom.xml
└── docker-compose.yml          ← infrastructure only (DBs + Kafka)
```

### Parent POM key sections

```xml
<packaging>pom</packaging>

<modules>
    <module>api-gateway</module>
    <module>auth-service</module>
    <module>subscription-service</module>
    <module>manuscript-service</module>
    <module>analysis-service</module>
    <module>translation-service</module>
    <module>coaching-service</module>
    <module>notification-service</module>
</modules>

<properties>
    <java.version>25</java.version>
    <spring-boot.version>4.0.3</spring-boot.version>
    <spring-cloud.version><!-- check start.spring.io for Boot 4.x compatible version --></spring-cloud.version>
    <jjwt.version>0.12.6</jjwt.version>
    <stripe.version>26.x.x</stripe.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### docker-compose.yml (infrastructure only — Spring Boot apps run in IntelliJ)

```yaml
services:
  postgres-auth:
    image: postgres:16
    ports: ["5433:5432"]
    environment:
      POSTGRES_DB: auth_db
      POSTGRES_PASSWORD: password

  postgres-subscription:
    image: postgres:16
    ports: ["5434:5432"]
    environment:
      POSTGRES_DB: subscription_db
      POSTGRES_PASSWORD: password

  postgres-manuscript:
    image: postgres:16
    ports: ["5435:5432"]
    environment:
      POSTGRES_DB: manuscript_db
      POSTGRES_PASSWORD: password

  postgres-analysis:
    image: pgvector/pgvector:pg16
    ports: ["5436:5432"]
    environment:
      POSTGRES_DB: analysis_db
      POSTGRES_PASSWORD: password

  postgres-translation:
    image: postgres:16
    ports: ["5437:5432"]
    environment:
      POSTGRES_DB: translation_db
      POSTGRES_PASSWORD: password

  postgres-coaching:
    image: postgres:16
    ports: ["5438:5432"]
    environment:
      POSTGRES_DB: coaching_db
      POSTGRES_PASSWORD: password

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    ports: ["9092:9092"]
    environment:
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_NODE_ID: 1
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qk
    ports: ["9092:9092"]
```

### Service ports

| Service | Port |
|---|---|
| api-gateway | 8080 |
| auth-service | 8081 |
| subscription-service | 8082 |
| manuscript-service | 8083 |
| analysis-service | 8084 |
| translation-service | 8085 |
| coaching-service | 8086 |
| notification-service | 8087 |

### IntelliJ setup steps

1. `File → New Project → Maven` — create `ssp-platform` parent (packaging=pom)
2. For each service: right-click parent → `New → Module → Spring Initializr`
3. View → Tool Windows → **Services** panel — run all 8 apps simultaneously
4. Install **Kafka** plugin in IntelliJ (Settings → Plugins) — inspect topics visually
5. `docker-compose up -d` once — all DBs and Kafka start, stay running

---

## 8. Build Phases

### Phase 0 — Foundation (Week 1)

**Goal:** All services start. Infrastructure runs.

- Create `ssp-platform` parent Maven project in IntelliJ
- Write `docker-compose.yml` for all 6 PostgreSQL DBs + Kafka
- Create empty Spring Boot module for each service via Spring Initializr
- Establish shared JWT secret (environment variable, same value in gateway + auth-service)
- Verify: all 8 apps start, connect to their DBs, no startup errors

**Done when:** `docker-compose up -d` starts cleanly. All Spring Boot services start without errors.

---

### Phase 1 — Identity & Routing (Weeks 2–3)

**Build:** Auth Service + API Gateway

- Auth Service: register, login, JWT issuance, token refresh
- API Gateway: route `/auth/**` without JWT check, all other routes require valid JWT
- JWT filter injects `X-User-Id` and `X-User-Tier` headers downstream
- Test end-to-end: register → login → get JWT → call protected route through gateway

**Done when:** Gateway correctly rejects requests with no/invalid JWT. Valid JWT passes through with user headers injected downstream.

---

### Phase 2 — Manuscript Ingestion (Week 4)

**Build:** Manuscript Service

- Move project CRUD and chapter ingestion code from SSP into Manuscript Service
- Apache Tika file parsing (copy dependency + TikaDocumentParserAdapter from SSP)
- Content-hash deduplication (already implemented in SSP — move as-is)
- Replace Spring `ApplicationEventPublisher` with Kafka producer publishing `chapter.ingested`
- Test: upload chapter file → `chapter.ingested` appears in Kafka (use IntelliJ Kafka plugin)

**Done when:** Chapter upload through gateway → Kafka topic has the event with correct payload.

---

### Phase 3 — Analysis Pipeline (Weeks 5–6)

**Build:** Analysis Service (SSP core extraction)

- Move all SSP analysis code into `analysis-service`
- Consume `chapter.ingested` from Kafka (replace `@EventListener` with `@KafkaListener`)
- Synchronous REST call to Subscription Service before starting: can-analyze check
- Keep all existing hexagonal architecture intact (ports, adapters, domain models)
- Publish `ChapterAnalyzedEvent` when pipeline completes
- Track per-book chapter completion: when all chapters of a Book reach ANALYZED → trigger Phase 3.5

**Done when:** Upload chapter → event flows `Manuscript → Kafka → Analysis` → characters and scenes extracted → `chapter.analyzed` event published in Kafka.

---

### Phase 3.5 — Book Completion Pipeline (Week 7, within Analysis Service)

**Build:** Book-level aggregate entity generation — runs once when a Book is fully analyzed.

- Detect when all chapters of a Book are `ANALYZED` → fire `book.analysis.complete` internal event
- `BookCharacterService` — for each character in the book, roll up their `CharacterState` snapshots
  into a `BookCharacter` record: personality summary, final emotional/arc/affiliation state, key moments
- `BookRelationshipService` — for each character pair, produce a one-line `BookRelationship` dynamics summary
- `BookWorldStateService` — compress `ProjectWorldState` at last chapter into a `BookWorldState` record
- `BookKeySceneService` — LLM selects 10–20 most narratively significant scenes → writes `BookKeyScene` records
- `BookSummaryService` — full prose book summary from chapter summaries + key events → stores as `BookSummary`
- Embed `BookCharacter` + `BookKeyScene` records in pgvector (type=`book_character`, `book_key_scene`)
  so they are RAG-searchable cross-book
- Update Book status to `COMPLETE`
- Publish `book.completed` Kafka event

**RAPTOR indexing also runs here:**
- Chunk all chapters of the book → cluster → summarize per cluster (Gemini 2.0 Flash)
- Store all levels (0=chunks, 1=cluster summaries, 2=chapter rollups, 3=arc rollup) in pgvector
  with `level` and `book_id` metadata

**Done when:** After marking all chapters analyzed, `GET /api/analysis/books/{id}/context-package`
returns BookSummary + BookCharacter list + BookWorldState + BookKeyScene list for that book.

---

### Phase 4 — RAPTOR Cross-Book Context & Coaching Queries (Week 8)

**Build:** PageIndex in Analysis Service

- `PageIndexNode` domain model (record with children list)
- `PageIndexBuilderService` — 3 LLM call types: leaf summary, chapter rollup, manuscript root
- `ProjectPageIndexJpaEntity` — stores full tree as JSONB
- `PageIndexPersistencePort` + adapter
- `PageIndexController` — exposes `/narrative-context` endpoint
- Trigger: after all chapters of a project are `ANALYZED`, build tree automatically

**Done when:** After analyzing all chapters, `GET /api/v2/analysis/projects/{id}/narrative-context?query=what+happened&upToChapter=5` returns a coherent narrative summary derived from tree traversal.

---

### Phase 5 — Translation (Week 8)

**Build:** Translation Service + upgraded 4-context assembly

- Move translation pipeline from SSP into `translation-service`
- Add `NarrativeContextClient` — REST call to Analysis Service's narrative-context endpoint
- Upgrade context assembly: 4 parallel calls (3 vector RAG + 1 PageIndex)
- Keep two-pass literary translation intact

**Done when:** Translation prompt includes both vector RAG character block AND PageIndex narrative context. Translated chapter text references story history correctly.

---

### Phase 6 — Subscription & Billing (Weeks 9–10)

**Build:** Subscription Service

- `plan_limits` table with FREE/BASIC/PRO/UNLIMITED tiers
- REST endpoint for Analysis Service: `can-analyze` check
- Stripe integration: checkout session creation + webhook handler
- Kafka consumer for `chapter.analyzed` and `translation.completed` → increment usage

**Done when:** Free tier user attempting to analyze an 11th chapter gets `403 LIMIT_EXCEEDED`. Stripe payment upgrades plan. 11th chapter analysis proceeds.

---

### Phase 7 — Coaching Features (Weeks 11–13)

**Build:** Coaching Service

- `CoachingReportService` — Narrative Health Report generation
  - Global sections (themes, overall arc) via PageIndex
  - Entity sections (character arcs, relationships) via Vector RAG
- `AuthorQueryService` + `QueryRouter` — intelligent hybrid Q&A routing
- `NarrativeHealthScore` — 0–100 weighted composite score
- Report sections cover: themes, pacing, character arc consistency, relationship evolution, tonal issues, underutilized characters

**Done when:** `POST /api/v2/coaching/projects/{id}/reports/generate` returns a structured JSON report with all sections and a health score.

---

### Phase 8 — Notifications (Week 14)

**Build:** Notification Service

- Kafka consumers for: `chapter.analyzed`, `page.index.ready`, `translation.completed`, `auth.password-reset`, `subscription.payment-failed`
- SendGrid or AWS SES integration for email delivery
- WebSocket handler for real-time dashboard push notifications

**Done when:** Analysis completing triggers an email to the author. Dashboard updates in real-time when translation finishes.

---

## 9. Hybrid RAG + PageIndex — Why and How

### The three data layers — none replaces the others

SSP maintains three distinct layers of narrative data. **PageIndex does not replace WorldState or CharacterState.** Each answers a different question.

| Layer | What it stores | Answers | Used by |
|---|---|---|---|
| **CharacterState** (SQL + pgvector) | Per-character snapshot per chapter: emotional state, arc stage, affiliation, loyalty | "Who is Aria at chapter 12? What was her state during the flashback?" | Translation RAG, character arc coaching |
| **ProjectWorldState** (SQL) | Cumulative rolling narrative summary updated after every chapter | "What has happened in the story up to chapter N?" — pre-built, zero LLM cost at query time | Translation Pass 1 prompt directly |
| **PageIndex tree** (SQL JSONB) | Hierarchical scene → chapter → arc → root summaries, built on-demand | "What are the manuscript's themes? Which arc has pacing issues?" | Coaching Service only |

```
Translation pipeline uses:
  ├── WHO   → CharacterState + Vector RAG        (character context)
  ├── WHERE → ProjectWorldState                  (narrative position — already built, free at query time)
  ├── WHAT  → Glossary + Vector RAG              (terminology)
  └── HOW   → StyleExample + Vector RAG          (prose style)
  PageIndex is NOT used in translation — WorldState already covers narrative continuity.

Coaching Service uses:
  ├── Global story questions  → PageIndex tree traversal
  ├── Character arc questions → CharacterState history
  ├── Relationship questions  → RelationshipState history
  └── Entity lookup           → Vector RAG
```

### What each retrieval approach handles

| Query type | Handled by | Why |
|---|---|---|
| "Who is this character?" | Vector RAG | Similarity search is exactly right |
| "What does this term mean?" | Vector RAG | Keyword-proximate lookup |
| "Match style for this scene type" | Vector RAG | Semantic similarity to past examples |
| "What has happened so far?" | **ProjectWorldState** | Pre-built cumulative summary, zero LLM cost at query time |
| "What was Aria's state at chapter 5?" | CharacterState | Temporal snapshot lookup by chapter number |
| "What are the themes of this manuscript?" | PageIndex global | Needs tree root traversal |
| "Which arc has pacing issues?" | PageIndex arc level | Needs structural arc comparison |
| "Does chapter 15 contradict chapter 3?" | PageIndex targeted | Needs two specific chapter summaries |
| "Is this character's arc consistent?" | CharacterState history | Granular per-chapter character data |

### PageIndex — Coaching-only, built on demand

PageIndex is **not part of the analysis pipeline**. It is built the first time an author requests a coaching report, then cached. Translation is unaffected.

```
Author requests coaching report
          ↓
Does project_page_indexes have a current tree?
  ├── YES → use existing tree
  └── NO  → build tree now (one-time), then proceed
             Rebuild trigger: chapterCount increased by ≥10 since last build
```

### Adaptive tree depth — prevents token explosion at scale

The root traversal prompt grows linearly with chapter count unless the tree has intermediate levels. SSP uses adaptive depth:

```
≤ 50 chapters   → 2-level: scenes → chapters → root
51–200 chapters → 3-level: scenes → chapters → arcs → root
200+ chapters   → 4-level: scenes → chapters → arcs → acts → root
```

Each hop only sees 3–10 summaries regardless of total manuscript size.

### PageIndex build cost by manuscript size (one-time per project)

Token assumptions: 3 scenes/chapter, leaf=400 tokens, chapter rollup=600 tokens, arc=7,900 tokens.
GPT-4o: $2.50/1M input, $10/1M output.

| Chapters | Tree depth | Total tokens | Build cost |
|---|---|---|---|
| 10 | 2-level | 19,300 | **$0.08** |
| 50 | 2-level | 91,300 | **$0.37** |
| 150 | 3-level | 317,700 | **$1.27** |
| 300 | 3-level | 634,100 | **$2.54** |
| 500 | 4-level | 1,066,200 | **$4.26** |

Cost per chapter stays at **~$0.008 regardless of size** — the adaptive tree absorbs the scale.

### Batch rebuild cost (triggered when ≥10 new chapters added)

| Base size | After new chapters | Rebuild cost |
|---|---|---|
| 50 → 60 | +10 chapters | $0.44 |
| 150 → 160 | +10 chapters | $1.35 |
| 300 → 320 | +20 chapters | $2.71 |

### PageIndex cost vs tier revenue

| Tier | Annual revenue | Worst-case build cost | % of revenue |
|---|---|---|---|
| Basic (50 ch) | $120 | $0.37 | 0.3% |
| Pro (150 ch) | $360 | $1.27 | 0.4% |
| Unlimited (500 ch) | $1,200 | $4.26 | 0.4% |

### The 5 prompts required

**Leaf summary prompt:**
```
Given this scene analysis, write 2 sentences capturing what happens and
its narrative/emotional significance in the story.
Scene type: {type} | Tone: {tone} | Summary: {summary}
```

**Chapter rollup prompt:**
```
Given these scene summaries from Chapter {N}, write one paragraph summarizing
the chapter's arc, key character moments, and how it advances the story.
Scenes: {joined scene summaries}
```

**Arc detection prompt (one call during build, for 3-level+ trees):**
```
Given these chapter summaries, group them into 3–7 narrative arcs.
Each arc should cover a coherent, self-contained story phase.
Return JSON: [{ "title": "...", "startChapter": N, "endChapter": N }]
Chapters: {joined chapter summaries}
```

**Arc/Act rollup prompt:**
```
Given these chapter summaries covering chapters {start}–{end},
write one paragraph summarizing this arc's key turning points,
character dynamics, and emotional trajectory.
Chapters: {joined chapter summaries}
```

### PageIndex retrieval — tool-calling (MCP-style agent), not fixed traversal

A fixed traversal prompt (step 1: pick arcs → step 2: pick chapters → step 3: answer) is blind
at each hop. The LLM cannot backtrack or explore two branches. The right approach is to give the
LLM **tools** and let it navigate the tree as an agent.

You do **not** need a literal MCP server for this. Spring AI's native `@Tool` + `.tools()` API
achieves the same pattern inside the Coaching Service JVM.

**Define navigation tools on CoachingService:**

```java
@Component
public class PageIndexTools {

    @Tool(description = "List all story arcs for this manuscript with their summary and chapter range")
    public List<ArcSummary> listArcs(@ToolParam String projectId) { ... }

    @Tool(description = "Get chapter summaries within a specific arc. Call after listArcs to drill down.")
    public List<ChapterSummary> getChapterSummaries(
            @ToolParam String projectId,
            @ToolParam String arcId) { ... }

    @Tool(description = "Get scene-level details for a specific chapter. Use when chapter summary is insufficient.")
    public List<SceneDetail> getSceneDetails(@ToolParam String chapterId) { ... }

    @Tool(description = "Get the full text of a chapter. Only call when scene details are still insufficient.")
    public String getFullChapterText(@ToolParam String chapterId) { ... }
}
```

**Wire into the coaching ChatClient call:**

```java
chatClient
    .prompt()
    .system(COACHING_SYSTEM_PROMPT)
    .user(userQuestion)
    .tools(pageIndexTools)          // Spring AI 2.0 API — gives LLM the tool definitions
    .options(ChatOptionsBuilder.builder()
        .maxToolCallRounds(8)       // cost guard — 3–5 calls is typical
        .build())
    .call()
    .content();
```

**How the LLM navigates (typical 3-question coaching query):**
```
User: "Does my protagonist's arc feel complete?"

LLM calls: listArcs("project-42")
  → [Arc 1: "The Awakening" ch1-8, Arc 2: "Descent" ch9-22, Arc 3: "Resolution" ch23-30]

LLM calls: getChapterSummaries("project-42", "arc-1")
  → [ch1: intro, ch2: first trial, ..., ch8: first failure]

LLM calls: getChapterSummaries("project-42", "arc-3")
  → [ch23: return, ..., ch30: final choice]

LLM: synthesizes arc 1 start + arc 3 end → answers the question
```

**Why tool-calling beats fixed traversal:**

| | Fixed 3-hop traversal | Tool-calling agent |
|---|---|---|
| LLM visibility | Blind at each hop — guesses from IDs | Sees retrieved text, decides next action |
| Multi-branch | No — commits to one path | Yes — can inspect Arc 1 and Arc 3 separately |
| Depth control | Hardcoded | Stops when it has enough — cheaper on simple queries |
| Backtracking | No | Yes — can go back up and try another branch |
| Cost (simple query) | 3 LLM calls always | 2–3 LLM calls |
| Cost (complex query) | 3 LLM calls (may miss info) | 5–8 LLM calls (but gets correct answer) |
| Spring AI support | Manual sequential calls | Native via `@Tool` + `.tools()` |

**Cost guard:** Set `maxToolCallRounds(8)`. A typical coaching query uses 3–5 rounds.
At $0.002/call average, worst case is $0.016 per coaching question — negligible.

---

## 10. Cost Impact — Author Coaching SaaS (Corrected)

> **Important clarification:** The Author Coaching SaaS is a standalone product for writers
> analyzing their *own* manuscripts. Translation is a separate product with separate costs.
> Costs below are coaching-only: analysis + RAPTOR indexing + coaching queries.

### Real word counts (researched)

| Book type | Words per volume | Tokens per volume |
|---|---|---|
| Short light novel (Konosuba, Mushoku Tensei early vols) | 45,000–50,000 | ~60,000–67,000 |
| Typical light novel (Re:Zero, Overlord, SAO) | 62,000–75,000 | ~82,000–100,000 |
| Standard western novel | 80,000–100,000 | ~107,000–133,000 |
| Epic fantasy | 150,000–300,000 | ~200,000–400,000 |

**Real series totals (measured):**
- Re:Zero web novel (arcs 1–8): ~3.2M words = ~4.26M tokens
- Overlord (18 volumes): ~1.2M words = ~1.6M tokens
- Mushoku Tensei (26 volumes): ~1.17M words = ~1.56M tokens
- Harry Potter series (7 books): ~1.08M words = ~1.44M tokens
- Wheel of Time (14 books): ~4.4M words = ~5.86M tokens

### What is RAPTOR?

RAPTOR (Recursive Abstractive Processing for Tree-Organized Retrieval) is a Stanford 2024
technique that builds a hierarchical tree of summaries over a document corpus. It solves the
core weakness of standard vector RAG: chunk-level search misses big-picture questions because
no single chunk contains the full answer.

```
                          ROOT SUMMARY
                     "This series is about..."
                      /                  \
              ARC SUMMARY            ARC SUMMARY
           "Chapters 1–30..."     "Chapters 31–60..."
             /        \               /        \
       CH SUMMARY  CH SUMMARY  CH SUMMARY  CH SUMMARY
         /  \  \
    chunk  chunk  chunk   ← actual text
```

At query time, all levels are searched simultaneously. Specific questions hit chunks at the
bottom. Series-wide questions ("what are the themes?") hit the top. Character arc questions
hit the middle. +20% absolute accuracy improvement over standard RAG on long-document QA
(Stanford QuALITY benchmark, ICLR 2024). No Java library exists — implemented directly in
Spring AI + pgvector using Apache Commons Math for k-means clustering.

---

### AI model benchmark data (researched, March 2026)

Source: Artificial Analysis Intelligence Index + Chatbot Arena human preference votes.

| Model | Intelligence Score | Arena Rank | Blended Cost/1M tokens |
|---|---|---|---|
| Claude Opus 4.6 | 46 | **#1 overall** | $10.00 |
| Claude Sonnet 4.6 | 44 | #13 | $6.00 |
| Gemini 2.5 Pro | 35 | #29 | $3.44 |
| Claude Haiku 4.5 | 31 | above avg | $2.00 |
| GPT-4.1 | 26 | #68 | $3.50 |
| Gemini 2.5 Flash | 21 | #71 | $0.85 |
| GPT-4.1-mini | 23 | above avg | $0.70 |
| Gemini 2.0 Flash | 19 | above avg | $0.26 |
| GPT-4.1-nano | ~13 | below avg | $0.17 |

**Critical finding:** GPT-4.1 (arena rank #68, score 26) is NOT competitive with Claude for
literary or narrative analysis tasks despite being OpenAI's 2025 flagship. It was optimised
for instruction-following and coding. For coaching and narrative analysis, Claude is the clear
choice. GPT-4.1's strength is JSON extraction precision, not creative intelligence.

### Model selection by task (platform chooses — users never see model names)

| Task | Best quality | Balanced (default) | Budget |
|---|---|---|---|
| Coaching / narrative analysis / Q&A | Claude Opus 4.6 | **Claude Sonnet 4.6** | Claude Haiku 4.5 |
| Character / scene extraction (JSON) | Claude Sonnet 4.6 | **GPT-4.1-mini** | Gemini 2.0 Flash |
| RAPTOR summarization (bulk) | GPT-4.1-mini | **Gemini 2.0 Flash** | Gemini 2.5 Flash-Lite |
| BookSummary / BookCharacter rollup | Claude Haiku 4.5 | **Gemini 2.0 Flash** | Gemini 2.0 Flash |
| Query routing / classification | GPT-4.1-mini | **GPT-4.1-nano** | Gemini 2.5 Flash-Lite |

Users choose the **language** they want responses in. The platform picks the best model
internally per task and per tier. AI provider selection is never exposed to the user.

### User-facing subscription tiers → internal model stacks

Users see three simple tiers. Internally each maps to a model stack:

```
FREE  ($0/month)
  Extraction:  Gemini 2.0 Flash         (budget)
  Coaching:    Claude Haiku 4.5         (budget — still Claude quality)
  RAPTOR:      Gemini 2.0 Flash
  Limits:      1 book, 10 queries/month

STANDARD  ($15/month)
  Extraction:  GPT-4.1-mini             (balanced)
  Coaching:    Claude Sonnet 4.6        (balanced — very close to Opus quality)
  RAPTOR:      Gemini 2.0 Flash
  Limits:      10 books, 100 queries/month, cross-book context

PREMIUM  ($30/month)
  Extraction:  Claude Sonnet 4.6        (best)
  Coaching:    Claude Opus 4.6          (best — #1 model in human preference)
  RAPTOR:      GPT-4.1-mini             (better narrative summaries)
  Limits:      Unlimited books, unlimited queries, cross-project context
```

What users see on the pricing page — no model names, no AI jargon:

```
Free       $0/mo   1 book · 10 coaching questions/mo · Standard analysis
Standard   $15/mo  10 books · 100 questions/mo · Advanced analysis · Series context
Premium    $30/mo  Unlimited · Highest quality AI · Cross-series context · Priority
```

The quality difference between Haiku and Opus is genuinely noticeable on complex editorial
feedback (deep character psychology, thematic consistency across volumes). Premium justifies
itself. If a better/cheaper model launches, the platform swaps it in — users see nothing change.

### Coaching SaaS cost per series (one-time ingestion + monthly usage)

**Token baseline:** Typical light novel volume = 65,000 words = ~87,000 tokens.
25 chapters/volume average. Two-pass translation context adds ~28,000 tokens per chapter call.

**5-volume series (~325k words, 125 chapters) — Standard tier:**

| Operation | Model | Cost |
|---|---|---|
| Character + scene analysis (125 chapters) | GPT-4.1-mini | ~$1.40 |
| WorldState updates | GPT-4.1-mini | ~$0.50 |
| RAPTOR tree build (all 5 volumes) | Gemini 2.0 Flash | ~$0.04 |
| Book completion pipeline × 5 | Gemini 2.0 Flash | ~$0.12 |
| **Total one-time ingestion** | | **~$2.06** |
| Per coaching query (Claude Sonnet 4.6) | | ~$0.08–0.15 |
| Monthly coaching (50 queries) | | ~$4–7.50 |

**10-volume series (~650k words, 250 chapters) — Standard tier:**

| Operation | Model | Cost |
|---|---|---|
| Analysis pipeline (250 chapters) | GPT-4.1-mini | ~$2.70 |
| RAPTOR indexing (all 10 volumes) | Gemini 2.0 Flash | ~$0.07 |
| Book completion pipeline × 10 | Gemini 2.0 Flash | ~$0.20 |
| **Total one-time ingestion** | | **~$2.97** |
| Per coaching query (Claude Sonnet 4.6) | | ~$0.10–0.20 |
| Monthly coaching (50 queries) | | ~$5–10 |

**25-volume series — Re:Zero/Overlord scale (~1.6M words, 625 chapters) — Premium tier:**

| Operation | Model | Cost |
|---|---|---|
| Analysis pipeline (625 chapters) | Claude Sonnet 4.6 | ~$9.50 |
| RAPTOR indexing (all 25 volumes) | GPT-4.1-mini | ~$0.45 |
| Book completion pipeline × 25 | Claude Haiku 4.5 | ~$1.50 |
| **Total one-time ingestion** | | **~$11.45** |
| Per coaching query, RAPTOR in Gemini 2.5 Pro 1M context | Claude Opus 4.6 | ~$0.20–0.50 |
| Monthly coaching (50 queries) | | ~$10–25 |

> **Why Gemini 2.5 Pro's 1M window eliminates complex cross-book retrieval:**
> RAPTOR summaries of a full 25-volume series = ~60,000–80,000 tokens — fits in one call
> under Gemini 2.5 Pro's cheaper $1.25/M tier. No tool-calling, no multi-hop traversal.
> Cross-book context is a single direct load. Free at query time beyond the base model cost.

### Cross-project context overhead

Loading `ProjectSeriesSummary` from referenced projects: ~500 tokens each → +$0.003 per query
for 2 referenced projects. Negligible. Glossary + StyleExamples RAG simply widens its
`project_id IN (...)` filter — no extra embedding call, no extra tokens.

### Revised tier economics (corrected models)

| Tier | Revenue/mo | AI cost/mo | Margin | Notes |
|---|---|---|---|---|
| Free | $0 | ~$0.80 | Loss leader | Claude Haiku keeps quality acceptable |
| Standard ($15) | $15 | ~$2.50 | **83%** | Claude Sonnet 4.6 coaching — most users here |
| Premium ($30) | $30 | ~$9.00 | **70%** | Claude Opus 4.6 — noticeably better on deep analysis |

Ingestion cost is one-time. A Standard user staying 6 months: $90 revenue, ~$2 ingestion + ~$15
query costs = **81% blended margin over 6 months**.

---

## 11. Summary View

### What you have now
```
SSP monolith — analysis + translation pipeline, working, hexagonal architecture
```

### What you're building
```
8-service platform:
  ├── 3 new services    Auth, Subscription, Coaching
  ├── 1 thin service    Notification (stateless event consumer)
  ├── 1 routing layer   API Gateway
  └── 3 extracted       Manuscript, Analysis, Translation (from SSP)
      └── Analysis gets PageIndex added on top
```

### What makes it architecturally distinctive
```
Domain hierarchy:
  Tenant → Project → Book/Volume → Chapter
  (Cross-book and cross-project context is user-controlled, not automatic)

Retrieval strategy (platform chooses model — users never pick AI provider):
  ├── Within-book:  Vector RAG on CharacterState, Glossary, StyleExamples
  ├── Cross-book:   BookCharacter RAG + ProjectSeriesSummary (direct load)
  ├── Cross-project: ProjectSeriesSummary of referenced projects (direct load)
  └── Global coaching: RAPTOR summaries in Gemini 2.5 Pro 1M context window

Language:
  User picks the language → AI responds in that language across all features.
  No AI provider selection exposed to the user ever.
```

### Build timeline (1 person, part-time)
```
Phase 0:   Week 1      Foundation setup (Tenant→Project→Book→Chapter schema)
Phase 1:   Weeks 2-3   Auth + Gateway
Phase 2:   Week 4      Manuscript Service (Book + Chapter ingestion)
Phase 3:   Weeks 5-6   Analysis Service (SSP extraction)
Phase 3.5: Week 7      Book Completion Pipeline (BookSummary, RAPTOR, BookCharacter)
Phase 4:   Week 8      RAPTOR cross-book context + Coaching query endpoint
Phase 5:   Week 9      Translation Service (optional — separate product)
Phase 6:   Weeks 10-11 Subscription + Stripe
Phase 7:   Weeks 12-14 Coaching Service (reports, Q&A, health score)
Phase 8:   Week 15     Notifications

Total: ~15-18 weeks to full platform
Monetizable (coaching): from Phase 7 onward
```

---

---

## 12. Series-Aware Domain Model

### The Hierarchy

```
Tenant
  └── Project  (a series, e.g. "Re:Zero" or "Mistborn", or a standalone work)
        │   project_references → other Projects in same Tenant (optional, user-controlled)
        ├── Shared across all books in this Project:
        │     Glossary, StyleExamples, Characters (canonical registry),
        │     ProjectSeriesSummary (rolling compressed series context)
        └── Book  (one volume / one novel)
              ├── Status: DRAFT → ANALYZING → COMPLETE
              └── Chapter  (individual chapter)
                    Status: PENDING → ANALYZING → ANALYZED | FAILED

Book-level aggregates (generated once when Book reaches COMPLETE):
  BookSummary        — comprehensive prose narrative of what happened in this book
  BookCharacter      — one record per character: final state + personality profile
  BookRelationship   — one record per character pair: final relationship state
  BookWorldState     — world/faction/political state at end of this book
  BookKeyScene       — 10–20 most narratively significant scenes in the book
  RaptorTree         — hierarchical RAPTOR index of all chapters in this book
```

**Tenant** — top-level owner. An individual author, team, or organization.
Every query is tenant-scoped. No tenant can see another's data.

**Project** — a series or standalone work. Holds settings shared across all books:
source language, genre, translation style, shared Glossary, shared StyleExamples, shared
character registry. A Project can optionally reference other Projects of the same tenant.

**Book** — one volume. Has its own status lifecycle. Chapters belong to Books, not Projects.

**Chapter** — unchanged internally. References `book_id` (not `project_id` as in the old model).

### Full Database Schema (Manuscript Service owns top 3 levels)

```sql
-- TENANT
tenants
  id BIGSERIAL PK
  name VARCHAR NOT NULL
  slug VARCHAR UNIQUE NOT NULL
  created_at TIMESTAMP

-- PROJECT
projects
  id BIGSERIAL PK
  tenant_id BIGINT FK → tenants.id
  title VARCHAR NOT NULL
  source_language VARCHAR NOT NULL          -- BCP-47 code (e.g. "ja", "en")
  target_languages VARCHAR[]               -- ["en", "ko", "zh"] — translation targets
  genre VARCHAR
  translation_style TEXT                   -- prose style notes for translator
  status VARCHAR                           -- DRAFT | IN_PROGRESS | COMPLETE | ARCHIVED
  created_at, updated_at TIMESTAMP

-- Cross-project references (user-controlled, same tenant only)
project_references
  id BIGSERIAL PK
  source_project_id BIGINT FK → projects.id
  referenced_project_id BIGINT FK → projects.id
  reference_type VARCHAR                   -- SAME_UNIVERSE | STYLE_ONLY
  tenant_id BIGINT FK → tenants.id         -- both projects must match this
  created_at TIMESTAMP

-- BOOK
books
  id BIGSERIAL PK
  project_id BIGINT FK → projects.id
  book_number INT NOT NULL                 -- ordering within the series (1, 2, 3...)
  title VARCHAR NOT NULL
  status VARCHAR                           -- DRAFT | ANALYZING | COMPLETE
  synopsis_user TEXT                       -- author-written synopsis (highest priority)
  synopsis_ai TEXT                         -- AI-generated (fallback if no user synopsis)
  created_at, updated_at TIMESTAMP

-- CHAPTER (owns book_id now, not project_id)
chapters
  id BIGSERIAL PK
  book_id BIGINT FK → books.id
  chapter_number INT NOT NULL
  title VARCHAR
  original_text TEXT
  content_hash VARCHAR(64)                 -- SHA-256 for deduplication
  status VARCHAR                           -- PENDING | PARSING | PARSED
  analysis_status VARCHAR                  -- PENDING | ANALYZING | ANALYZED | FAILED
  created_at, updated_at TIMESTAMP
```

### Book-Level Aggregate Tables (Analysis Service)

```sql
-- BOOK SUMMARY (generated once, user can override)
book_summaries
  id BIGSERIAL PK
  book_id BIGINT UNIQUE FK → books.id
  ai_summary TEXT NOT NULL                 -- comprehensive AI-generated narrative summary
  user_summary TEXT                        -- user override; if set, this is used
  key_events JSONB                         -- [{title, chapterNumber, significance}]
  unresolved_threads TEXT                  -- cliffhangers / open plot threads
  themes TEXT                             -- core themes of this book
  tone TEXT                               -- overall emotional/narrative tone
  generated_at TIMESTAMP

-- BOOK CHARACTER (one per character per book, rolled up from CharacterState)
book_characters
  id BIGSERIAL PK
  book_id BIGINT FK → books.id
  character_id BIGINT FK → characters.id   -- canonical character registry
  name VARCHAR NOT NULL
  aliases JSONB                            -- known names in this book
  role VARCHAR                             -- PROTAGONIST | ANTAGONIST | SUPPORTING | MINOR
  personality_summary TEXT                 -- stable traits (who they ARE as a person)
  final_emotional_state VARCHAR            -- emotional state at END of book
  final_arc_stage VARCHAR                  -- arc position at END of book
  final_affiliation TEXT
  final_loyalty_score INT
  key_moments TEXT                         -- what they did that matters going forward
  carries_forward BOOLEAN DEFAULT true     -- expected in next book?

-- BOOK RELATIONSHIP (one per significant pair per book)
book_relationships
  id BIGSERIAL PK
  book_id BIGINT FK → books.id
  character1_id BIGINT FK → characters.id
  character2_id BIGINT FK → characters.id
  relationship_type VARCHAR                -- ALLY | ENEMY | FAMILY | ROMANTIC | NEUTRAL | MENTOR
  final_affinity INT                       -- numeric score at end of book
  dynamics_summary TEXT                    -- "uneasy alliance after the betrayal arc"

-- BOOK WORLD STATE
book_world_states
  id BIGSERIAL PK
  book_id BIGINT UNIQUE FK → books.id
  faction_summary TEXT                     -- power dynamics at end of book
  geography_notes TEXT                     -- key locations and their status
  political_changes TEXT                   -- what changed in the world
  open_conflicts TEXT                      -- unresolved tensions carrying into next book

-- BOOK KEY SCENES (10–20 pivotal scenes per book)
book_key_scenes
  id BIGSERIAL PK
  book_id BIGINT FK → books.id
  chapter_number INT NOT NULL
  title VARCHAR
  scene_type VARCHAR
  tone VARCHAR
  summary TEXT                             -- 2–3 sentence description
  narrative_significance TEXT              -- why this scene matters going forward

-- PROJECT SERIES SUMMARY (rolling, updated after each book completes)
project_series_summaries
  id BIGSERIAL PK
  project_id BIGINT UNIQUE FK → projects.id
  current_summary TEXT                     -- compressed ~500 word series context
  covers_up_to_book_number INT             -- which book this summary includes
  updated_at TIMESTAMP
```

### What Stays Unchanged

These entities and their purposes are unchanged from the SSP monolith:
- `characters` — canonical registry per project (scoped to Project, not Book)
- `character_states` — per-character per-chapter temporal snapshots (within-book)
- `character_relationships` — current/latest relationship state
- `relationship_states` — temporal snapshots per chapter (within-book)
- `scenes` — per-chapter scene analysis
- `glossary` — project-wide terminology
- `style_examples` — project-wide few-shot translation examples
- `project_world_states` — rolling world state updated per chapter (within-book continuity)

### Cross-Book Context Strategy — Four Layers

When the user enables "use previous books" on analysis or coaching:

```
Layer 0 — Cross-project (if project_references configured):
  ProjectSeriesSummary of each referenced project  →  ~500 tokens each, direct DB load
  Glossary RAG search expanded to include referenced project IDs  →  no extra tokens
  StyleExamples RAG expanded to include referenced project IDs  →  no extra tokens

Layer 1 — ProjectSeriesSummary (current project):
  Rolling ~500-word series summary updated after each completed book  →  direct load, always included

Layer 2 — BookWorldState of immediately prior book (Book N-1):
  ~300–500 tokens  →  direct DB load, tells AI "world state entering this book"

Layer 3 — BookCharacter RAG:
  Embed book_characters in pgvector (type="book_character")
  At query time: detect characters in current chapter text → RAG by character name
  Fetches most recent prior-book record per character  →  3–6 records, ~300 tokens each

Layer 4 — BookKeyScene RAG:
  Embed book_key_scenes in pgvector (type="book_key_scene")
  At query time: search by current chapter's themes/content
  Fetches 1–3 pivotal scenes from prior books  →  ~150–200 tokens each
```

**Total cross-book context overhead per chapter**: ~3,000–6,000 tokens regardless of series length.
The RAPTOR tree absorbs the scale — you never grow linearly with volume count.

**Gemini 2.5 Pro 1M window for coaching queries:**
RAPTOR summaries of a full 25-volume series fit in ~60,000–80,000 tokens.
Coaching queries load all RAPTOR summaries into context in one call.
No tool-calling tree traversal needed at this scale. Direct, cheap, accurate.

### Cross-Project Context — User-Controlled

```
project_references table controls what is shared:

reference_type = SAME_UNIVERSE:
  ├── Share ProjectSeriesSummary (world context)
  ├── Share Glossary (terminology, proper nouns)
  ├── Share StyleExamples (if author style is consistent)
  └── Share BookWorldState from last completed book of referenced project

reference_type = STYLE_ONLY:
  └── Share StyleExamples only (different universe, same author voice)

NOT shared in either case:
  ├── CharacterState, BookCharacter (characters are series-specific)
  ├── ChapterTranslation (output, not context)
  └── Subscription/billing data (never)

Security: both source_project_id and referenced_project_id MUST belong
to the same tenant_id. Enforced at the database adapter layer.
```

**User workflow:**
In Project B settings → "Reference another project" → select Project A from dropdown
(only shows projects belonging to same tenant) → choose reference type → save.
All subsequent analysis and coaching in Project B automatically includes Project A's context.

### Language Selection — User-Controlled, Model-Agnostic

```
User selects language (e.g. "Japanese", "Korean", "Spanish") in their profile settings.
All AI responses — coaching reports, Q&A answers, narrative summaries — are generated
in that language by injecting a language instruction into every system prompt:

  "Respond entirely in {userLanguage}. All analysis, explanations, and recommendations
   must be written in {userLanguage}."

This works regardless of which AI model is selected by the platform.
The user never sees which model is used. The platform optimizes internally.
```

### Entities That Are NOT in the Original SSP

These are all new entities required by the series-aware architecture:

```
Tenant                   — new (multi-tenancy)
Book                     — new (between Project and Chapter)
BookSummary              — new (book-level rollup)
BookCharacter            — new (final character state per book)
BookRelationship         — new (final relationship state per book)
BookWorldState           — new (world state at book end)
BookKeyScene             — new (pivotal scenes per book)
ProjectSeriesSummary     — new (rolling cross-book series context)
project_references       — new (cross-project linking)
RaptorTree levels        — new (stored in pgvector with level + book_id metadata)
```

Everything else in the SSP monolith carries forward unchanged.

---

## 13. Role-Based Access Control (RBAC)

### Role Hierarchy

```
SUPER_ADMIN   — Platform-level. Access to everything. Internal use only.
ADMIN         — Platform-level. Access to everything. We will differentiate
                from SUPER_ADMIN in a later stage.
TENANT_ADMIN  — Tenant-level. Full access within their tenant.
                Can create Projects. Can assign users to Projects.
PROJECT_ADMIN — Project-level. Full access within their Project(s).
                Can add users to Projects they admin.
USER          — Basic member. Access only to Projects they are a member of.
```

This is a **three-level hierarchy**:
- **Platform level** — SUPER_ADMIN, ADMIN (stored on the user record)
- **Tenant level** — TENANT_ADMIN or MEMBER (stored in `tenant_members`)
- **Project level** — PROJECT_ADMIN or MEMBER (stored in `project_members`)

A user can belong to multiple tenants (e.g. a contractor working for two companies).
A user can be PROJECT_ADMIN of some projects and plain MEMBER of others within the same tenant.
TENANT_ADMIN automatically has full access to all projects in their tenant — no `project_members`
record needed.

---

### Access Control Matrix

| Action | SUPER_ADMIN | ADMIN | TENANT_ADMIN | PROJECT_ADMIN | USER |
|---|---|---|---|---|---|
| Create / delete tenants | ✓ | ✓ | ✗ | ✗ | ✗ |
| View all tenants | ✓ | ✓ | ✗ | ✗ | ✗ |
| Create project | ✓ | ✓ | ✓ own tenant | ✗ | ✗ |
| Delete project | ✓ | ✓ | ✓ own tenant | ✗ | ✗ |
| Add user to tenant | ✓ | ✓ | ✓ own tenant | ✗ | ✗ |
| Add user to project | ✓ | ✓ | ✓ own tenant | ✓ own project | ✗ |
| Upload manuscript | ✓ | ✓ | ✓ | ✓ | ✓ if member |
| Trigger analysis | ✓ | ✓ | ✓ | ✓ | ✓ if member |
| Trigger translation | ✓ | ✓ | ✓ | ✓ | ✓ if member |
| View coaching reports | ✓ | ✓ | ✓ | ✓ | ✓ if member |
| Manage subscription | ✓ | ✓ | ✓ own tenant | ✗ | ✗ |
| Configure project references | ✓ | ✓ | ✓ own tenant | ✓ own project | ✗ |

---

### Database Schema (Auth Service owns all of this)

```sql
-- USERS
users
  id BIGSERIAL PK
  email VARCHAR UNIQUE NOT NULL
  password_hash VARCHAR NOT NULL
  platform_role VARCHAR NOT NULL DEFAULT 'USER'
    -- values: SUPER_ADMIN | ADMIN | USER
    -- TENANT_ADMIN and PROJECT_ADMIN are stored in membership tables, not here
  is_active BOOLEAN DEFAULT true
  created_at TIMESTAMP

-- TENANT MEMBERSHIP (who belongs to which tenant and in what capacity)
tenant_members
  id BIGSERIAL PK
  user_id BIGINT FK → users.id
  tenant_id BIGINT NOT NULL            -- references tenants in Manuscript Service
                                        -- no DB FK (cross-service), enforced by app
  role VARCHAR NOT NULL                 -- TENANT_ADMIN | MEMBER
  invited_by BIGINT FK → users.id
  joined_at TIMESTAMP
  UNIQUE (user_id, tenant_id)

-- PROJECT MEMBERSHIP (who belongs to which project and in what capacity)
project_members
  id BIGSERIAL PK
  user_id BIGINT FK → users.id
  project_id BIGINT NOT NULL           -- references projects in Manuscript Service
                                        -- no DB FK (cross-service), enforced by app
  tenant_id BIGINT NOT NULL            -- denormalised for fast tenant-scoped queries
  role VARCHAR NOT NULL                 -- PROJECT_ADMIN | MEMBER
  invited_by BIGINT FK → users.id
  joined_at TIMESTAMP
  UNIQUE (user_id, project_id)
```

---

### What the JWT Contains

The Gateway validates the JWT and injects headers downstream. The JWT payload is kept lean —
project-level roles are NOT in the JWT (they can be many and change frequently).

**JWT payload:**
```json
{
  "userId": 123,
  "email": "user@example.com",
  "platformRole": "USER",
  "tenantId": 456,
  "tenantRole": "TENANT_ADMIN"
}
```

**Headers the Gateway injects into every downstream request:**
```
X-User-Id:       123
X-Platform-Role: USER
X-Tenant-Id:     456
X-Tenant-Role:   TENANT_ADMIN
```

**Project-level role checks** are done by the service itself:
- Manuscript Service calls `auth-service` at `GET /internal/members/project/{projectId}/role?userId={id}`
- OR Manuscript Service maintains its own `project_members` mirror (simpler, eventually consistent via Kafka)

The second approach (local mirror) is preferred — no synchronous cross-service call on every request.

---

### How Each Service Enforces Access

**API Gateway** — enforces:
- JWT present and valid on all routes except `/auth/**`
- Injects `X-User-Id`, `X-Tenant-Id`, `X-Platform-Role`, `X-Tenant-Role`
- Blocks SUPER_ADMIN-only admin routes from non-SUPER_ADMIN tokens

**Auth Service** — enforces:
- Only SUPER_ADMIN or ADMIN can create tenants
- Only TENANT_ADMIN (of that tenant) can invite users to tenant
- Users can only see their own profile unless ADMIN+

**Manuscript Service** — enforces:
- `POST /projects` → requires `X-Tenant-Role: TENANT_ADMIN` or platform admin
- `GET /projects/{id}` → user must be tenant member AND (project member OR tenant admin)
- `POST /projects/{id}/members` → PROJECT_ADMIN of that project, or TENANT_ADMIN
- All queries automatically filtered by `X-Tenant-Id` — a user can never see another tenant's data

**Analysis, Translation, Coaching Services** — enforce:
- User must be a member of the project owning the book/chapter being accessed
- Check against local `project_members` mirror table (synced via Kafka events)

---

### Kafka Events for Membership Sync

When membership changes in Auth Service, it publishes events so downstream services
can update their local mirrors:

```
auth.member.added    → { userId, projectId, tenantId, role }
auth.member.removed  → { userId, projectId, tenantId }
auth.tenant.member.added   → { userId, tenantId, role }
auth.tenant.member.removed → { userId, tenantId }
```

Analysis, Translation, and Coaching Services subscribe to these and maintain a local
`project_members` table (userId, projectId, role) for fast access checks without
calling Auth Service on every request.

---

### Auth Service — New Endpoints

```
POST   /auth/register              — create account (platform role = USER by default)
POST   /auth/login                 — returns JWT + refresh token
POST   /auth/refresh               — new JWT from refresh token

POST   /tenants/{id}/members       — invite user to tenant (TENANT_ADMIN only)
DELETE /tenants/{id}/members/{uid} — remove user from tenant (TENANT_ADMIN only)

POST   /projects/{id}/members      — add user to project (PROJECT_ADMIN or TENANT_ADMIN)
DELETE /projects/{id}/members/{uid}— remove user from project

GET    /users/me                   — current user profile + memberships
GET    /internal/members/project/{projectId}/role?userId=  — internal use by other services
```

---

### Roles in the Build Phases

**Phase 0** — add `platform_role` column to users table schema design. No enforcement yet.

**Phase 1 (Auth + Gateway)** — implement full role system:
- Register/login issues JWT with `platformRole`, `tenantId`, `tenantRole`
- Gateway injects role headers
- Basic role check on create-tenant endpoint (SUPER_ADMIN/ADMIN only)

**Phase 2 (Manuscript)** — enforce project and tenant membership:
- `POST /projects` checks TENANT_ADMIN
- Project access checks against `project_members` table
- Publish `auth.member.*` events when membership changes

**All later phases** — consume `auth.member.*` events, maintain local mirrors, enforce
project membership on all resource access.

---

*Document generated: 2026-03-21. Updated: 2026-03-22*
*Related docs: microservice-build-guide.md*

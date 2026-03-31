<!--
Sync Impact Report
===================
Version change: N/A → 1.0.0 (initial ratification)
Modified principles: N/A (initial creation)
Added sections:
  - Core Principles (5 principles)
  - Performance Standards
  - Development Workflow
  - Governance
Removed sections: N/A
Templates requiring updates:
  - .specify/templates/plan-template.md: ✅ compatible (Constitution Check section exists)
  - .specify/templates/spec-template.md: ✅ compatible (Success Criteria supports performance metrics)
  - .specify/templates/tasks-template.md: ✅ compatible (phase structure aligns with incremental delivery)
  - .specify/templates/checklist-template.md: ✅ compatible (category structure supports principle-based checks)
  - .specify/templates/agent-file-template.md: ✅ compatible (no principle-specific references)
Follow-up TODOs: None
-->

# PetStar Constitution

## Core Principles

### I. Performance-First Engineering

Every feature and optimization MUST be justified by measurable
performance data. Changes to hot paths (voting, ranking, entry listing)
MUST include Before/After metrics from k6 load testing.

- All API endpoints MUST meet p95 < 500ms under target load
- Database queries MUST be analyzed for N+1 problems before merge
- Connection pool, thread pool, and cache configurations MUST be
  tuned with empirical data, not defaults
- Redis caching MUST be used for read-heavy, latency-sensitive paths
  (rankings, duplicate vote detection)

**Rationale**: PetStar's core value proposition is demonstrating
production-grade performance optimization. Every architectural decision
traces back to measurable throughput and latency improvements.

### II. Data Consistency Under Concurrency

The system MUST guarantee correctness of vote counts, rankings, and
user constraints even under concurrent access. Eventual consistency
is acceptable only when paired with verification mechanisms.

- Vote uniqueness MUST be enforced at both application level (Redis
  Set check) and database level (UNIQUE constraint on entry_id + member_id)
- Hot-spot writes (vote counts) MUST use appropriate concurrency
  control: pessimistic locks, atomic updates, or async queuing
- The hybrid strategy (Redis immediate + SQS eventual DB persistence)
  MUST include a scheduled consistency verification job
  (VoteConsistencyScheduler)
- Idempotency MUST be guaranteed for all SQS consumers via
  check-and-record pattern

**Rationale**: A voting platform that loses or duplicates votes
destroys user trust. Correctness is non-negotiable even when
optimizing for speed.

### III. Layered Architecture Discipline

All code MUST follow the strict layered separation:
Controller → Service → Repository → Entity.

- Controllers handle HTTP concerns only: request validation
  (@Validated), authentication (@LoginUser), response wrapping
  (ApiResponse)
- Services own business logic and transaction boundaries
  (@Transactional)
- Repositories encapsulate data access; complex queries use QueryDSL
  custom implementations
- Entities are JPA-managed; all relationships MUST use
  FetchType.LAZY by default
- DTOs MUST be used for API boundaries; entities MUST NOT leak
  to controllers
- Cross-layer calls MUST follow the direction above; no skipping
  layers (e.g., Controller calling Repository directly)

**Rationale**: Strict layering enables independent optimization of
each layer (e.g., swapping QueryDSL queries without touching services)
and keeps the codebase navigable as features grow.

### IV. Measurable, Phase-Based Delivery

Features and optimizations MUST be delivered in discrete phases, each
with a clear goal, technique, and measurable outcome.

- Each phase MUST define: focus area, technique applied, and
  success metric
- Load tests (k6) MUST be run at phase boundaries to validate
  improvements
- Phase results MUST be documented with Before/After comparisons
- Regression in previously optimized metrics MUST be investigated
  before proceeding

**Rationale**: Phase-based delivery with metrics creates a clear
optimization narrative and prevents regressions from going unnoticed.

### V. Simplicity and YAGNI

Start with the simplest solution that meets current requirements.
Add complexity only when justified by measured bottlenecks or
concrete requirements.

- Do NOT add caching, async processing, or distributed locks
  preemptively; add them when load testing reveals the need
- Prefer standard Spring Boot mechanisms over custom frameworks
- Configuration MUST use Spring profiles (local, dev, prod) with
  sensible defaults
- New dependencies MUST solve a proven problem; avoid speculative
  library adoption

**Rationale**: Over-engineering obscures the optimization narrative
and makes the system harder to reason about. Complexity is justified
only by evidence.

## Performance Standards

- **Response Time**: All API endpoints MUST achieve p95 < 500ms
  under the defined target load (300 concurrent users)
- **Error Rate**: System MUST maintain < 1% error rate under peak load
- **Throughput**: System MUST sustain >= 300 RPS for read-heavy
  endpoints (challenge listing, rankings, entries)
- **Database**: No query SHOULD exceed 100ms under normal load;
  queries exceeding this threshold MUST be optimized (indexing,
  fetch join, or caching)
- **Vote Processing**: End-to-end vote response time MUST be < 100ms
  (Redis path); DB persistence via SQS is allowed up to 5 seconds
  eventual consistency window
- **Load Testing**: k6 scenarios MUST cover warmup (10 VUs),
  normal (100 VUs), peak (200 VUs), and stress (300 VUs) stages
- **Monitoring**: Prometheus metrics (connection pool, JVM heap,
  request latency) MUST be available in all non-local environments

## Development Workflow

- **Branch Strategy**: Feature branches off main; merge via PR after
  validation
- **Commit Discipline**: Each commit SHOULD represent a logical unit
  of work; commit messages MUST reference the phase or feature context
- **Testing Gates**: Load tests MUST pass defined thresholds before
  merging performance-related changes
- **Code Review Focus**: Reviews MUST check for N+1 queries,
  missing indexes, improper transaction boundaries, and concurrency
  correctness
- **Environment Profiles**: Code MUST work across local (LocalStack),
  dev (AWS), and prod (AWS) profiles without conditional compilation
- **Infrastructure as Code**: AWS resources MUST be managed via
  Terraform; manual console changes are prohibited for production

## Governance

This constitution is the authoritative reference for PetStar's
engineering principles. All code changes, architecture decisions,
and optimization work MUST comply with the principles defined above.

- **Compliance**: All PRs and code reviews MUST verify adherence to
  these principles. Deviations MUST be documented with justification.
- **Amendments**: Changes to this constitution require:
  1. Written proposal describing the change and rationale
  2. Version bump following semantic versioning (see below)
  3. Update of the Last Amended date
  4. Propagation check across dependent templates
- **Versioning Policy**:
  - MAJOR: Principle removal or incompatible redefinition
  - MINOR: New principle or materially expanded guidance
  - PATCH: Clarifications, wording fixes, non-semantic refinements
- **Guidance File**: Runtime development guidance is maintained in
  `context/CLAUDE.md` and MUST remain consistent with this constitution.

**Version**: 1.0.0 | **Ratified**: 2026-03-10 | **Last Amended**: 2026-03-10

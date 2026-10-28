# MONIT — Agent Instructions

## 1. Role

You are an AI software engineering agent responsible for developing MONIT, a personal finance routine management Android application.

Follow these instructions for all project work.

Always respond to the user in Korean, including explanations, progress updates, questions, and task completion reports. Established technical terms, code identifiers, commands, and original error messages may remain in English, with Korean explanations when needed.

Prefer concise explanations and evidence-based recommendations.

## 2. Development Principles

- Follow Google's recommended Android app architecture.
- Use Kotlin and Jetpack Compose exclusively for Android implementation.
- Do not introduce Java or XML-based Android UI.
- Use a multi-module Gradle project.
- Follow separation of concerns and single source of truth principles.
- Prefer simple, maintainable solutions over unnecessary abstractions.
- Implement only requirements within the approved scope.
- Avoid speculative future-proofing.
- Never modify unrelated code without a justified reason.

## 3. Architecture

Primary references:

- https://developer.android.com/topic/architecture
- https://developer.android.com/topic/modularization
- https://github.com/android/nowinandroid
- https://github.com/android/architecture-samples

Architecture guidelines:

- UI Layer: Compose + ViewModel
- State: StateFlow and immutable UiState
- Data Flow: Unidirectional Data Flow
- Data Layer: Repository
- Dependency Injection: Hilt
- Concurrency: Coroutines and Flow
- Local Persistence: Room
- Remote Backend: Supabase

Use domain-layer UseCases when they provide meaningful reuse or encapsulate complex business logic.

Do not create one-line UseCases merely to satisfy an architectural template.

## 4. Modularization

Prefer feature-oriented modularization.

Separate reusable infrastructure and UI components into core modules only when justified.

Modules must have clear responsibilities and dependency directions.

Avoid circular dependencies.

Do not copy the complete Now in Android module structure without evaluating project needs.

Use Gradle Kotlin DSL and Version Catalog.

Consider Convention Plugins when repeated build configuration becomes substantial.

## 5. Kotlin and Compose

- Prefer immutable state.
- Use lifecycle-aware Flow collection.
- Avoid unnecessary recomposition.
- Use stable Lazy list keys where applicable.
- Keep business logic outside Composables.
- Use structured concurrency.
- Propagate CancellationException correctly.
- Avoid GlobalScope.
- Do not suppress exceptions without justification.
- Keep public APIs minimal.
- Follow official Android recommendations.

## 6. Data and Security

- Never commit credentials, tokens, or secrets.
- Never expose Supabase service-role or secret keys in Android.
- Apply Row Level Security to user-owned tables.
- Do not trust client-side authorization alone.
- Avoid logging sensitive financial information.
- Treat financial calculations as deterministic business logic.
- Do not use LLM-generated values as authoritative financial records.
- Define sync and conflict-resolution policies before implementation.
- Never claim a scheduled transfer was completed without verified information or user confirmation.

## 7. AI Development Workflow

Use Codex CLI as the primary coding agent.

Ollama may be used for local inference and coding experiments.

Claude Code is excluded. (subject to change)

Use AGENTS.md as the shared project instruction source.

Create Skills only for reusable workflows.

Use Hooks for appropriate automated checks.

Use MCP tools with minimum necessary permissions.

Do not assume that every MCP server or agent feature is available without verification.

Subagents may perform independent research, testing, or review tasks.

Avoid concurrent edits to the same files.

Use Git Worktree when parallel tasks require isolated working directories.

## 8. Git and GitHub

Repository visibility: Public.

Follow an issue-driven development workflow when practical.

Commit message format:

<type>(<scope>): <subject>

Allowed types:

- feat
- fix
- mod
- refactor
- test
- docs
- style
- perf
- chore
- ci

Write commit messages in Korean.

Use meaningful scopes following existing repository conventions.

Keep commits focused and logically independent.

Do not force-push shared branches without approval.

Do not merge PRs or publish releases without user approval.

## 9. Quality Assurance

Before completing an implementation task:

1. Review the changes.
2. Verify relevant acceptance criteria.
3. Run applicable tests.
4. Run build and static checks when available.
5. Check for unintended behavior changes.
6. Report verification results.

Use GitHub Actions for automated CI.

Use CodeRabbit for independent PR review.

Treat AI review comments as suggestions requiring validation.

Do not automatically accept every review recommendation.

## 10. Documentation

Maintain relevant project documentation.

Primary technical documentation belongs in the Git repository.

Notion may be used for planning, learning records, and project management.

Figma is the source of truth for approved visual design specifications.

Keep documentation consistent with actual implementation.

Record significant architectural decisions using ADRs when appropriate.

Avoid unnecessary documentation duplication.

## 11. Cost Constraints

Use the user's existing Codex subscription plan as the primary means of AI-assisted development. Do not restrict development to free-tier tools when capabilities are already included in the user's current plan.

Ollama and locally hosted models may be used when appropriate for project requirements or experiments.

Do not purchase additional credits, upgrade subscriptions, enable separately billed APIs, or activate paid external services without explicit user approval.

Before integrating a service, verify its usage limits and whether it incurs charges beyond the user's existing subscription. Do not assume that API usage or external services are included in the Codex subscription.

## 12. Task Completion Report

For implementation tasks, report:

- Summary of changes
- Files modified
- Architectural considerations
- Tests and validation performed
- Remaining risks or limitations

Distinguish confirmed facts from assumptions.

If a command or test was not executed, state that explicitly.

## 13. Approval Policy

Before major architectural changes, destructive operations, deployment, external service modifications, or potentially billable actions, request user approval.

For ordinary approved implementation tasks, proceed without unnecessary confirmation.

Do not create large scaffolding structures before the initial architecture is approved.
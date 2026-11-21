# Shared contracts

`shared/` documents how the modules coordinate shared data boundaries. Role D coordinates changes with the affected producers and consumers. The current contract is JSON, specified in [API_CONTRACT.md](../docs/API_CONTRACT.md). Kotlin clients and servers can define separate DTOs against it.

**Current state.** This directory contains documentation. A shared Gradle module, Kotlin DTO library and code generator are deferred. There is no setup or test command for `shared/`.

## When to add Kotlin sharing

Revisit an optional Kotlin Multiplatform DTO module when stable types are being duplicated by multiple Kotlin targets and a spike verifies Kotlin/Wasm and JVM builds, serialization parity, and independent Vercel and Railway build contexts. Keep the public JSON contract authoritative. A future iOS or Android target may provide a concrete reason to revisit the tradeoff after the Web MVP.

If approved, start with serialization DTOs only and preserve platform-neutral dependencies. Introduce the smallest viable build integration, run old and new consumer fixtures on each supported target, and confirm the same JSON field names, null rules, enum handling and timestamp encoding. Record the adoption decision and migration in [DECISIONS.md](../docs/DECISIONS.md).

## Making a contract change

Raise the impact in the PR with before-and-after JSON, its verification plan, and deployment compatibility. Update the protocol and examples with the coordinated producer/consumer changes. Use the module tests and integration smoke test to verify the contract across the deployed path. See [shared interfaces](INTERFACES.md) and the [development workflow](../docs/DEVELOPMENT_WORKFLOW.md).


## Challenge boundary

The JetBrains submission needs genuine sharing across client targets. Put shared client workflow in the frontend KMP project's `commonMain` first. Adopt a separate shared Kotlin module only when multiple projects need it. [Challenge plan](../docs/CHALLENGES.md)

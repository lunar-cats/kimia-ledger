# ADR 0001: Java 25 LTS, Spring Boot 4, Gradle Kotlin DSL multi-module build

Status: Accepted · Date: 2026-11-13

## Context
Kimia Ledger is a long-lived financial service. We need a runtime with a long support window, a mainstream
framework for the adapter layer, and a build that enforces module boundaries.

## Decision
- **Java 25 LTS** (Oracle premier support to Sep 2030). Non-LTS releases (26, 27, 28) are skipped; next move is 29 LTS.
- **Spring Boot 4.x** (current stable, 4.1.2 at kickoff) for wiring and adapters only. Domain and application modules never import it.
- **Gradle (Kotlin DSL)**, multi-module, version catalog with exact versions, wrapper pinned with SHA-256,
  `-Xlint:all -Werror`, Spotless + google-java-format.

## Consequences
- One toolchain to maintain; JDK upgrades happen on the LTS cadence only.
- Spring Boot 4 renamed starters (`spring-boot-starter-webmvc`); artifact names come from the 4.x BOM.
- Every dependency bump is a reviewed PR (Renovate); nothing floats.
- Gradle cross-project configuration lives in the root build script until a convention plugin is justified.

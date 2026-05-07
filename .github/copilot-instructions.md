# Copilot Instructions for net-perf-monitor

## AI Context Files

This project maintains compact AI context files in the `docs/` directory. These files are optimised for low token consumption and contain everything needed to understand the project without reading every source file.

**Always read `docs/` files first before exploring the codebase.** They provide:

| File | Purpose | Read When |
|---|---|---|
| `docs/PROJECT_CONTEXT.md` | Tech stack, dependencies, build commands, output files, project layout | Always (first file to read) |
| `docs/ARCHITECTURE.md` | Thread model, class map, data flow, performance thresholds | Understanding code organisation or concurrency |
| `docs/DOMAIN_MODEL.md` | Every data class, config class, and all their fields with types and semantics | Adding/modifying data structures or understanding what a class holds |
| `docs/CONVENTIONS.md` | Naming rules, patterns, error handling, reporting rules, Windows UTF-8 rules | Writing new code or following project style |
| `docs/COMMON_TASKS.md` | Step-by-step guides for adding URLs, probes, fields, thresholds, checks | Implementing features or making changes |

## Auto-Update Rule

**When making any code changes, you MUST also update the relevant `docs/` files to reflect those changes.** This keeps the AI context accurate and prevents stale documentation.

Examples:
- Adding a new probe class → update `ARCHITECTURE.md` (thread model + class map), `COMMON_TASKS.md`
- Adding a new data field to any metrics class → update `DOMAIN_MODEL.md`
- Adding a new YouTube quality check → update `ARCHITECTURE.md` (thresholds table)
- Adding a new CLI flag or property key → update `PROJECT_CONTEXT.md`
- Changing a threshold constant → update `ARCHITECTURE.md`
- Changing a naming or coding pattern → update `CONVENTIONS.md`
- Adding a new reporting step → update `ARCHITECTURE.md` (data flow)

## Workflow

1. Read `docs/PROJECT_CONTEXT.md` to understand the project.
2. Read the specific `docs/` file relevant to your task (see table above).
3. Only then explore source files if the docs don't have enough detail.
4. Implement the code change.
5. Update the affected `docs/` files before finishing.

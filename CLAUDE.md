# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

"Zai" — a Java CLI warehouse assistant that calls an LLM (Nvidia-hosted `meta/llama-3.3-70b-instruct`) with OpenAI-style tool calling. It reasons over an SQLite "digital twin" of a warehouse (Products, Sales, Bins) plus a purchase-order CSV, tells the worker where to put arriving stock, and notifies managers via Telegram on emergencies.

## Commands

Windows/bash (paths use `;` as classpath separator):

```bash
# Compile all sources
javac -cp "lib/*" src/*.java

# Run the interactive agent (requires tools.Json and warehouse_demo.db in cwd)
java -cp "lib/*;src" AIAgent

# Rebuild the SQLite schema + seed data (run these from the project root; each drops/recreates its table)
java -cp "lib/*;src" WarehouseDatabaseSetup
java -cp "lib/*;src" BinDatabaseSetup
java -cp "lib/*;src" ProductDatabaseSetup
java -cp "lib/*;src" SalesDatabaseSetup
```

Linux/macOS: replace `;` with `:` in the classpath.

There is no build system, no linter, no test runner. Compiled `.class` files live alongside `.java` in `src/` and are gitignored.

## Architecture

### Control flow

`AIAgent.main` is a REPL that appends each worker message to `conversationHistory` (a `JsonArray`) and calls `callAPI` → `processAIResponse`. The assistant reply can be text (printed and stored) or `tool_calls`, which are dispatched by `dispatchTool` and followed by a recursive `processAIResponse` call. Recursion is bounded by `MAX_DEPTH = 8`; when hit, the next call sets `allowTools = false` to force a text reply.

### Tool schemas live in `tools.Json` (note capital J)

`getToolsJson()` reads `tools.json` at runtime — Windows' case-insensitive FS makes this work despite the on-disk filename being `tools.Json`. Don't rename either side without fixing the other. The schemas are sent to the model unchanged; adding a tool means editing both `tools.Json` and the `switch` in `AIAgent.dispatchTool`.

### Deterministic overrides of the model

Several behaviors are enforced in Java rather than trusting the LLM:

- **PO matching** (`buildPoMatchMessage`): after `readLocalPurchaseOrder` returns, the code parses the CSV itself, finds the row matching `lastArrivingProductId`, and injects a synthetic `role: user` message starting with `[SYSTEM PO CHECK]` into the history. The model is instructed to route based on that injected message. Do not move this logic into the prompt.
- **Arrival quantity gate**: `readLocalPurchaseOrder` refuses to run until `lastArrivingQuantity > 0`. When the guard trips (`QUANTITY_MISSING`), `processAIResponse` short-circuits and emits a direct question to the worker instead of recursing.
- **Confirmation gate**: `updateBinStatus` is rejected unless `workerJustConfirmed()` finds `done`/`placed`/`confirmed`/`ok`/`yes` in the most recent real user message (injected `[SYSTEM ...]` messages are skipped). If blocked, the code emits a direct "reply 'done' when placed" message using `findLastBinId()` to pull the bin ID out of recent tool outputs.
- **Terminal one-shots**: `reportAccident` and `clearAisle` stop the tool-call chain after one successful call and emit a canned reassurance, preventing the model from re-reporting.
- **Quantity extraction**: every worker turn runs `tryUpdateArrivingQuantity` — a regex that picks standalone integers (word-boundary anchored so `K15VC` is ignored) and stores the result in `lastArrivingQuantity`.

If you change any of these guards, also update `SYSTEM_INSTRUCTION` at the top of `AIAgent.java` so the prompt matches.

### State between turns

Two statics hold cross-turn state: `lastArrivingProductId` (set by `getProductAnalysis` / `searchProductByDescription` regex) and `lastArrivingQuantity`. Both are reset to sentinel values after `updateBinStatus` or `reportAccident` succeeds — this is how the agent "finishes a task" and is willing to accept a new one.

### Modules

- `AIAgent.java` — REPL, LLM HTTP call (with 429 retry/backoff), tool dispatch, state machine, all the guards above.
- `WarehouseSkills.java` — product/bin lookups, `findOptimalBin` SQL (accessibility score keyed to sales velocity), Telegram notification helper. `reportAccident`/`clearAisle` delegate DB writes to `InventoryTools`.
- `InventoryTools.java` — all writes to the `Bins` table: `updateBinStatus` (two-slot `Product1`/`Product2` model, auto-computes `Half`/`Full`), `blockBinsByLocation`, `clearBinsByLocation` (location parsing normalizes `Aisle 2` → `A2` prefix).
- `LocalFileTools.java` — thin CSV file reader.
- `*DatabaseSetup.java` — standalone `main`s that create/seed the SQLite schema. Run order doesn't matter for correctness, but `BinDatabaseSetup` drops and reseeds 54 bins (`A1..A3` × `S1..S2` × `L1..L3` × `B1..B3`); level controls weight/volume caps and `accessibility_score` (L1=5, L2=3, L3=1).

### Bin ID format

`A{aisle}-S{shelf}-L{level}-B{bin}` (e.g. `A2-S1-L3-B2`). The regex `[A-Z]\d+-S\d+-L\d+-B\d+` in `findLastBinId` depends on this; update both if the format changes.

## Secrets

`AIAgent.API_KEY` (Nvidia) and `WarehouseSkills.BOT_TOKEN` + `MANAGER_CHAT_IDS` (Telegram) are hard-coded. Treat them as compromised — do not extend this pattern. If you need to add another credential, thread it through an env var instead.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

"Zai" — a Java CLI warehouse assistant that calls an LLM (Nvidia-hosted `meta/llama-3.3-70b-instruct`) with OpenAI-style tool calling. It reasons over an SQLite "digital twin" of a warehouse (Products, Sales, Bins) plus a customer-order CSV, tells the worker where to put arriving stock, and notifies managers via Telegram on emergencies.

## Commands

All sources are in the `com.hackproject` package. Windows/bash (paths use `;` as classpath separator):

```bash
# Compile all sources into bin/
javac -d bin -cp "lib/*" src/com/hackproject/*.java

# Run the interactive agent (requires tools.json, CO_April20.csv, and warehouse_demo.db in cwd)
java --enable-native-access=ALL-UNNAMED -cp "lib/*;bin" com.hackproject.AIAgent

# Rebuild the SQLite schema + seed data (run these from the project root; each drops/recreates its table)
java -cp "lib/*;bin" com.hackproject.WarehouseDatabaseSetup
java -cp "lib/*;bin" com.hackproject.BinDatabaseSetup
java -cp "lib/*;bin" com.hackproject.ProductDatabaseSetup
java -cp "lib/*;bin" com.hackproject.SalesDatabaseSetup
```

Linux/macOS: replace `;` with `:` in the classpath.

There is no build system, no linter, no test runner. Compiled `.class` files go to `bin/` and are gitignored. Delete stray `.class` files from the old default-package layout (e.g. `src/*.class`, `out/*.class`, project-root `*.class`) if you see them — running with `-cp src` against the old bare-`AIAgent` class will silently load the pre-rename build.

## Architecture

### Control flow

`AIAgent.main` is a REPL that appends each worker message to `conversationHistory` (a `JsonArray`) and calls `callAPI` → `processAIResponse`. The assistant reply can be text (printed and stored) or `tool_calls`, which are dispatched by `dispatchTool` and followed by a recursive `processAIResponse` call. Recursion is bounded by `MAX_DEPTH = 8`; when hit, the next call sets `allowTools = false` to force a text reply.

### Tool schemas live in `tools.json`

`getToolsJson()` reads `tools.json` at runtime. The schemas are sent to the model unchanged; adding a tool means editing both `tools.json` and the `switch` in `AIAgent.dispatchTool`.

The nine registered tools are: `readLocalCustomerOrder`, `getProductAnalysis`, `findOptimalBin`, `searchProductByDescription`, `listWarehouseInventory`, `findProductLocation`, `updateBinStatus`, `reportAccident`, `clearAisle`. The first six are read-only; `updateBinStatus`, `reportAccident`, `clearAisle` mutate state.

### System instruction protocols

`SYSTEM_INSTRUCTION` at the top of `AIAgent.java` defines five named protocols the model follows:

- **PROTOCOL OMEGA** — emergency/accident response; triggers `reportAccident` and halts further tool calls.
- **PROTOCOL CLEAR** — aisle reopening; triggers `clearAisle` as a terminal one-shot.
- **PROTOCOL QUERY** — read-only inventory lookups (`listWarehouseInventory`, `findProductLocation`); no writes permitted.
- **PROTOCOL BATCH** — batch delivery entry point; triggers `startBatchDelivery`, which parses the PO and loads all items into `batchQueue`. Java then drives sequencing automatically via `advanceBatch()`.
- **DELIVERY WORKFLOW** — the main 5-step guided process: identify product → confirm quantity → check PO → find optimal bin → instruct worker → wait for confirmation → commit via `updateBinStatus`.

If the Java guards are changed, update `SYSTEM_INSTRUCTION` to match.

### `findOptimalBin` SQL logic

The optimal bin query in `WarehouseSkills` scores bins by sales velocity tier:

- Monthly sales ≥ 80 units → target `accessibility_score = 5` (level L1, most reachable)
- Monthly sales ≥ 30 units → target `accessibility_score = 3` (level L2)
- Below 30 → target `accessibility_score = 1` (level L3, deepest)

Within the tier, SQL ordering prefers: `Half` bins first (consolidation), same-product match, ascending actual `accessibility_score`. Weight and volume caps are checked against existing bin contents via a JOIN to the Products table.

### Deterministic overrides of the model

Several behaviors are enforced in Java rather than trusting the LLM:

- **CO matching** (`buildPoMatchMessage`): after `readLocalCustomerOrder` returns, the code parses the CSV itself, finds the row matching `lastArrivingProductId`, and injects a synthetic `role: user` message starting with `[CO MATCH FOUND]` or `[NO CO MATCH]` into the history. The model is instructed to route based on that injected message. Do not move this logic into the prompt. (The helper is still named `buildPoMatchMessage` from the pre-rename days — rename if you touch it, but it's not load-bearing.)
- **Arrival quantity gate**: `readLocalCustomerOrder` refuses to run until `lastArrivingQuantity > 0`. When the guard trips (`QUANTITY_MISSING`), `processAIResponse` short-circuits and emits a direct question to the worker instead of recursing.
- **Confirmation gate**: `updateBinStatus` is rejected unless `workerJustConfirmed()` finds `done`/`placed`/`confirmed`/`ok`/`yes` in the most recent real user message (injected `[SYSTEM ...]` messages are skipped). If blocked, the code emits a direct "reply 'done' when placed" message using `findLastBinId()` to pull the bin ID out of recent tool outputs.
- **Terminal one-shots**: `reportAccident` and `clearAisle` stop the tool-call chain after one successful call and emit a canned reassurance, preventing the model from re-reporting.
- **Quantity extraction**: every worker turn runs `tryUpdateArrivingQuantity` — a regex that picks standalone integers (word-boundary anchored so `K15VC` is ignored) and stores the result in `lastArrivingQuantity`.

If you change any of these guards, also update `SYSTEM_INSTRUCTION` at the top of `AIAgent.java` so the prompt matches.

### State between turns

Two statics hold cross-turn state: `lastArrivingProductId` (set by `getProductAnalysis` / `searchProductByDescription` regex) and `lastArrivingQuantity`. Both are reset to sentinel values after `updateBinStatus` or `reportAccident` succeeds — this is how the agent "finishes a task" and is willing to accept a new one.

Three additional statics manage batch mode: `batchQueue` (a `LinkedList<BatchItem>` of remaining PO items), `batchTotalItems` (count at batch start, never decremented), and `batchItemIndex` (1-based index of the item currently being processed). All three are set by `startBatchDelivery` and advanced by `advanceBatch()`, which is called from `processAIResponse` after `updateBinStatus` succeeds in batch mode. `advanceBatch()` injects either a `[BATCH NEXT ITEM]` or `[BATCH COMPLETE]` user-role system message into the history, which the model acts on immediately in the next recursive call. `workerJustConfirmed()` treats `[batch...]` messages as a hard stop (returns false) rather than skipping, ensuring each item's confirmation is not re-used for the next item.

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

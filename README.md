# Zai — Intelligent Warehouse Restock Manager
Frontend repo : https://github.com/Mingzhe0324/ZAI-Frontend.git  

TRY OUR APP : https://zai-1054928413411.asia-southeast1.run.app/  

<img width="300" height="300" alt="qr" src="https://github.com/user-attachments/assets/f3ad8ae3-8cbc-4bef-af6e-2efbbca29035" />


**Domain:** Fintech  
**Target users:** Small-to-medium electrical trading warehouses (e.g. *Chunley*) — businesses that operate as a warehouse but still serve walk-in local customers for daily needs.

---

## The Problem

In SME warehouses, the moment a delivery truck arrives, a worker has to answer three questions at once:

1. **Is any of this stock already promised to a customer order today?** — if yes, it should not be binned at all.
2. **Where is the best bin to put the rest?** — depends on product weight, volume, sales velocity, customer base, blocked areas, and remaining bin capacity.
3. **What if something goes wrong?** — a forklift fails, a spill happens, an aisle is blocked.

Today this is solved by walking around, asking the manager, guessing, or memorising. The result: **wasted time, wasted space, miscommunication, and unsafe routing.** A worker who is new to the floor cannot make these decisions at all.

Zai is built so that any worker — new or experienced — can make the *optimal* placement decision in seconds, just by talking to the system.

---

## The Solution

Zai is a **stateful, tool-using AI agent** that turns unstructured worker chat ("a truck of 60 K15VC arrived") into a structured, multi-step workflow:

> understand intent → query the right database → cross-check the customer order CSV → run the math for the best bin → instruct the worker → commit the placement → handle exceptions (accidents, blocked aisles) along the way.

If the AI is removed, the workflow collapses — the databases, CSV file, and Telegram notifier cannot coordinate themselves.

---

## Why It Doesn't Hallucinate

| Concern | How Zai handles it |
| --- | --- |
| **AI model** | `meta/llama-3.3-70b-instruct` (via Nvidia-hosted endpoint) |
| **Architecture** | AI Agent with OpenAI-style tool calling — the model never invents a bin ID, weight, or stock count; it must call a tool to read the database |
| **Quality of decisions** | Bin selection is a **mathematical query**, not a generated guess — weight capacity, volume capacity, accessibility score, and sales velocity are evaluated in SQL |
| **Safety guardrails** | Java-side gates enforce confirmation, quantity checks, and emergency keyword detection *before* any write to the database |

The LLM is the reasoning layer. The math, the data, and the writes are deterministic.

---

## Main Functions

Mapped to the hackathon brief — *unstructured input → multi-step reasoning → tool orchestration → structured output*.

### 1. Chat (Unstructured Input → Reasoning)
Worker types in natural language; Zai identifies whether it is a delivery, a query, an accident, or a clarification, then routes the workflow accordingly.

### 2. Customer Order Cross-Check (Dynamic Cross-Docking)
Before binning anything, Zai reads the local **customer order CSV** and intercepts stock that is already sold for the day — those units are routed straight to the packing counter instead of wasting a bin.

### 3. Find Optimal Bin *(the core algorithm)*
Given an arriving product and quantity, Zai picks the bin that satisfies **all** of:
- (i) quantity to be placed
- (ii) product weight
- (iii) product volume
- (iv) bin's remaining capacity
- (v) blocked / unblocked status
- (vi) product's sales velocity

**Decision logic:**
1. **Set accessibility threshold** — fast-moving items (≥80 sales/month) target the most reachable level (score 5); medium movers target score 3; slow movers target score 1. Premium real-estate is reserved for fast movers.
2. **Filter** — keep only bins that are `Empty` or `Half`, unblocked, not excluded, and can fit ≥1 unit by both weight and volume.
3. **Rank** — prefer `Half` bins (consolidate stock) → bins already holding the same product (affinity) → lowest allowed accessibility score (don't waste premium bins).
4. **Compute capacity** — units placeable in a bin = `min(weight capacity, volume capacity)`.
5. **Return assignment** — split across multiple bins automatically if one bin cannot absorb the full quantity.

### 4. Accident Report System
Worker describes an incident in natural language → Zai blocks every bin in the affected aisle, notifies all managers via Telegram, and stops suggesting that aisle until a `clear` signal is received.

### 5. Inventory Lookups & Directory Map
Read-only queries: *"is there any K15VC in the warehouse?"*, *"what is K15VC?"*, *"where is SCH-E8331?"*. Backed by a live web dashboard showing capacity analytics, sales velocity, and a 54-bin directory map.

### 6. Manager Notifications (Structured Output)
Telegram alerts for: customer order arrivals, accident reports, and aisle clearances — each with location, product, quantity, and priority.

---

## How to Use (Example Prompts)

Just chat with Zai like a colleague.

**Delivery arrival**
```
A truck of 60 K15VC arrived
```
Zai will check the customer order file, ask for missing info if needed, route any pre-sold units to the packing counter, and tell you exactly which bin(s) to fill.

**Inventory query**
```
What is K15VC?
Is there any K15VC in our warehouse?
Where is SCH-E8331?
```

**Emergency**
```
There is forklift failure at A1
```
Zai blocks Aisle A1 immediately, alerts the managers, and reroutes any new placements away from A1 until you say:
```
A1 is clear
```

**New worker, no product ID memorised?**
```
A pallet of grey 3-blade ceiling fans just arrived
```
Zai resolves the description to a product ID via the product database, then continues the normal flow.

---

## How to Run  

TRY OUR APP : https://zai-1054928413411.asia-southeast1.run.app/  

<img width="300" height="300" alt="qr" src="https://github.com/user-attachments/assets/f3ad8ae3-8cbc-4bef-af6e-2efbbca29035" />  


> All sources live in the `com.hackproject` package. Compiled classes go to `bin/`.
> Windows uses `;` as the classpath separator; Linux/macOS uses `:`.

**Compile**
```bash
javac -d bin -cp "lib/*" src/com/hackproject/*.java
```

**Run the interactive CLI agent**
```bash
java --enable-native-access=ALL-UNNAMED -cp "lib/*;bin" com.hackproject.AIAgent
```

**Run the HTTP server for the web dashboard** *(port 8080)*
```bash
java --enable-native-access=ALL-UNNAMED -cp "lib/*;bin" com.hackproject.WarehouseController
```

**Rebuild / reseed the SQLite database**
```bash
java -cp "lib/*;bin" com.hackproject.WarehouseDatabaseSetup
java -cp "lib/*;bin" com.hackproject.BinDatabaseSetup
java -cp "lib/*;bin" com.hackproject.ProductDatabaseSetup
java -cp "lib/*;bin" com.hackproject.SalesDatabaseSetup
```

Required at runtime in the working directory: `tools.json`, `CO_April20.csv`, `warehouse_demo.db`.

---

## Architecture at a Glance

```
Worker chat ──▶ AIAgent (LLM + tool dispatch + state machine)
                  │
                  ├── Tools ──▶ SQLite digital twin  (Bins · Products · Sales)
                  ├── Tools ──▶ Customer Order CSV
                  └── Tools ──▶ Telegram Bot (manager alerts)
                                 │
Web dashboard ◀── WarehouseController (HTTP :8080) ◀── DatabaseManager
```

- **Bins** — `A{aisle}-S{shelf}-L{level}-B{bin}`, each with weight cap, volume cap, accessibility score, and a two-slot product model.
- **Products** — ID, name, weight, volume, description.
- **Sales** — historical sales used to derive sales velocity tiers.

---

## Team

Teoh Jun Hong · Isaac Toh · Leow Zhen Xun · Chong Ming Zhe







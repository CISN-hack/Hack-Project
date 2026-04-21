# Warehouse Stock Manager
- How to run the backend for now ? (Not working without Zai API)
* 1 Download extension : Java pack , SQLite , SQLite viewer
* 2 Open terminal and type : exit then hit enter
* 3 type : javac -cp "lib/*" src/*.java
* 4 type : java -cp "lib/*;src" AIAgent
* 5 Start your conversation with AI
- Warehouse : E-commerce warehouse  (Supplier goods delivered -> workers handle -> based on customer's Purchase Order(PO) )

## Main Idea
- **Step 1 (Unstructured Input)**: A warehouse worker types in a chat: "A new pallet of 500 iPhones,50 heavy mini-fridges and 7 light bars just arrived. Where do I put them? Here is a picture of Aisle 4."

- **Step 2 (API Interactions & Multi-Step Reasoning)**: The GLM doesn't just guess. It actively triggers tools:  
  * **Tool 1**: Queries the **Purchase Database API** -> *"7 light bars occurs in recent Purchase Order"*
  * **Tool 2**: Queries the **Sales Database API** -> *"iPhones sell fast, fridges sell slow."*
  * **Tool 3**: Queries the **Product API** -> *"iPhones are 100g, fridges are 40kg."* (LLM need to do the calculation by himself)
  * **Tool 4**: Queries the **Warehouse Database API** -> *"Aisle 4 can only hold 200kg, and is currently empty."*

- **Step 3 (Edge Case Handling)**: ...Fridge is too heavy for the upper level...//...Closure of Aisle 4 due to failure of forklift
    
- **Step 4 (Dynamic Task Orchestration)**: Instead of failing, the GLM dynamically alters the workflow. It replies to the worker:"Fridges to Zone C, Aisle 9, Shelves 1-3. iPhones to Zone F, Aisle 6, Shelves 3, **light bars put near packing area**.I have updated the database to reserve this space."  

## How stock is locate (Digital Twin Table)
- **Note**: Warehouse contains multiple Zones (e.g., Cold Storage, Heavy Goods, Fast-Moving).  
**Zones** contain multiple **Aisles**.  
Aisles contain multiple **Shelves** (or Racks).  
Shelves contain individual **Bins** (or Slots).
  
- **binAdd** :  
bin_id: (e.g., **"ZH-A4-S2-B12"** for Zone Heavy, Aisle 4, Shelf 2, Bin 12)  
status: (e.g., "Empty", "Partially Full", "Full")  
max_weight_capacity: 100kg  
current_weight: 40kg  
max_volume: 2 cubic meters  
current_items: [List of product IDs currently stored here]

## Test Case Scenario
- **The Overweight Pallet**: The user asks to put 500kg of goods in an aisle, but the database says the shelf can only hold 200kg, forcing the AI to find a new location.

- **The "Missing Item" Ambiguity**: The user asks where to put "the new shipment," but forgets to say what the shipment is, forcing the AI to ask a clarifying follow-up question.

- **The "Blocked Aisle" Exception**: The user says, "Put the new stock in Aisle 4," but the AI has been informed that Aisle 4 is currently blocked due to a forklift breakdown, forcing it to reroute the worker.

-**The "Viral Product" Opportunity**: The AI notices that a specific product is suddenly selling much faster than usual (e.g., due to a viral TikTok video) and proactively suggests moving that product to a more accessible location for faster picking.

-**The "New Worker" Scenario**: A temporary worker who is unfamiliar with the warehouse layout asks for guidance on where to put a new shipment, and the AI provides a simple, conversational interface to direct them.
## Usefull Notes
### The Core Difference: "System of Record" vs. "System of Intelligence"
Standard warehouses use a Warehouse Management System (WMS). A WMS is essentially a giant database. It is a System of Record.

What a WMS does: A worker puts a box on Shelf 4, scans a barcode, and the WMS records: "Box A is on Shelf 4." It is purely reactive. It tracks what happened.

Your AI Agent is a System of Intelligence.

What your AI does: It doesn't just record where things are; it strategizes where things should go before the worker even moves the box.

The Point of Your Project (Your Hackathon USP)
Standard systems lack dynamic, cross-departmental reasoning. Your AI bridges the gap between sales forecasting and physical warehouse labor.

Here is why your project is valuable:

1. Dynamic Slotting (The "Sales Velocity" Angle)
Traditional WMS systems are often static. If a shelf is assigned to "Electronics," all electronics go there.
Your AI agent looks at external data. If it sees that a specific brand of headphones is going viral on TikTok and sales are spiking, it intervenes. When the next shipment arrives, the AI tells the worker: "Skip the normal electronics aisle. Put these right next to the packing station (Aisle 1) because we are going to ship 500 of them tomorrow." This saves workers miles of walking and speeds up delivery.

2. Handling Real-Time Exceptions
A standard tracking system breaks when reality doesn't match the database. If a forklift breaks down and blocks Aisle 2, a standard WMS still tells workers to put things in Aisle 2, causing a traffic jam.
Your AI Agent can be informed: "Aisle 2 is blocked." It immediately recalculates and orchestrates a new workflow, dynamically rerouting workers to alternative shelves that fit the weight and size constraints of their pallets.

3. The "Conversational Interface" for Workers
Enterprise software is notoriously clunky. New warehouse workers spend weeks learning how to use complex RF scanners and legacy database terminals.
With your agent, the interface is natural language. A temporary worker just types (or speaks): "I have a pallet of dog food, but the assigned shelf is broken." The AI instantly cross-references the database, finds a safe alternative, and replies: "Understood. Reroute to Zone B, Shelf 9. I will update the inventory records for you."

How to Frame This for the Judges
When your team of four presents this, lean into this narrative:

"Warehouses already know where their stock is. That is a solved problem. Our AI Agent solves the unsolved problem: knowing where stock should be right now to maximize packing efficiency, based on real-time sales data and physical constraints."

Does this help clarify the business value of your agent? We can start designing the mock API calls to simulate this "sales data to shelf location" logic if you are ready.








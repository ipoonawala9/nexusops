# ADR-0011: Inventory — a locked level projection over an append-only ledger, orders that move stock

- Status: Accepted
- Date: 2026-10-08
- Spec: docs/superpowers/specs/2026-10-08-inventory-mvp-design.md

## Context
Phase 6 adds stock. Stock must never go negative, every change must be explainable, and two people selling the last
units at the same moment must not both succeed. Products already live in the catalog (Phase 4); suppliers and
customers are directory parties (ADR-0008).

## Decision
1. **`stock_levels` is a projection, `stock_movements` the books.** One level row per (product, warehouse) holds
   on-hand and reserved; every on-hand change appends one movement in the same transaction. The app role has no UPDATE
   or DELETE on movements, so the ledger is append-only at the database. CHECKs keep `on_hand ≥ 0`,
   `0 ≤ reserved ≤ on_hand`, and each movement's sign matching its kind.
2. **One writer.** Only `StockLedger` changes stock. It creates missing level rows (`insert … on conflict do nothing`),
   then takes row locks in ascending (product, warehouse) order, so concurrent operations on the same items serialize
   and can't deadlock each other. Shortages are checked after locking. Operations that resolve a warehouse from a
   request (counts, transfers, order create and edit, confirm, rule create) take that warehouse's row `FOR SHARE` until
   commit, and archiving takes it `FOR UPDATE` before checking the warehouse is unused, so an archive waits for
   in-flight writers and later writers see it archived. Receipts, fulfilment and cancellation write stock against a
   warehouse that their open order already keeps from being archived.
3. **Orders move stock at their commitment points.** A purchase order receives into stock (partial receipts, never
   above the ordered quantity). A sales order reserves everything on confirm (or refuses with every shortage), issues
   on fulfil and releases on cancel. Drafts touch nothing, and parties get the SUPPLIER or CUSTOMER role only when an
   order is placed or confirmed. Every state change takes the order's optimistic-lock version before it touches
   stock, so two concurrent changes to one order cannot both move stock: the loser gets 409 and its stock work rolls back.
4. **Document numbers** (`PO-00001`, `SO-00001`) come from a per-tenant `number_sequences` row updated in the order's
   transaction, so numbers are unique and gap-free among committed orders: the increment rolls back with the order.
5. **Reorder suggestions are a deterministic rule** (blueprint §5.4, rules first): below minimum when available + on
   order < min; suggest max − (available + on order); explain with 30-day usage and days of cover. Suggestions become
   DRAFT purchase orders that a person reviews and places.
6. Read models (stock list, suggestions, overview) are SQL with an explicit `tenant_id` predicate on top of RLS, like
   ADR-0010's aggregates. Every Inventory permission belongs to module INVENTORY.

## Consequences
- The level can be rebuilt from the ledger, and tests prove they agree after counts, transfers, receipts, fulfilment and cancellation.
- Lock order is a rule every future stock writer must follow; keeping all writes in `StockLedger` enforces it.
- Forecasting beyond the rule (Phases 11–12), valuation, lots, serials and bins, units-of-measure conversion,
  partial fulfilment, back-orders and returns, and supplier price lists are later work (spec §1 non-goals).

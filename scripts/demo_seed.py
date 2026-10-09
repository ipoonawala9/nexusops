#!/usr/bin/env python3
"""Seed a local demo workspace ("Deccan Traders") through the public API.

Local development only: talks to the stack started by `make up-all` (API through http://localhost:3000) and reads the
verification email from Mailpit (http://localhost:8025). Uses only the standard library.

    python3 scripts/demo_seed.py            # workspace URL "deccan-traders"
    python3 scripts/demo_seed.py --slug demo-2   # another copy

Sign in at http://localhost:3000/login with the workspace URL, DEMO_EMAIL and DEMO_PASSWORD below.
"""
import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "http://localhost:3000/api/v1"
MAILPIT = "http://localhost:8025/api/v1"
DEMO_EMAIL = "owner@deccan-traders.test"
DEMO_PASSWORD = "Deccan demo 2026!"  # local demo value only


class Api:
    def __init__(self):
        self.token = None

    def call(self, method, path, body=None, ok=(200, 201, 204)):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(API + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if self.token:
            req.add_header("Authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(req) as res:
                raw = res.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            if e.code in ok:
                return None
            sys.exit(f"{method} {path} failed with {e.code}: {detail}")

    def get(self, path):
        return self.call("GET", path)

    def post(self, path, body=None):
        return self.call("POST", path, body if body is not None else {})

    def put(self, path, body):
        return self.call("PUT", path, body)


def verification_token(email):
    deadline = time.time() + 30
    pattern = re.compile(r"/verify-email\?token=([^\s\"'<&]+)")
    while time.time() < deadline:
        q = urllib.parse.quote(f'to:"{email}"')
        with urllib.request.urlopen(f"{MAILPIT}/search?query={q}") as res:
            found = json.loads(res.read())
        for summary in found.get("messages") or []:
            with urllib.request.urlopen(f"{MAILPIT}/message/{summary['ID']}") as res:
                msg = json.loads(res.read())
            m = pattern.search(f"{msg.get('Text', '')}\n{msg.get('HTML', '')}")
            if m:
                return urllib.parse.unquote(m.group(1))
        time.sleep(0.5)
    sys.exit(f"No verification email for {email} in Mailpit within 30 s")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--slug", default="deccan-traders")
    args = parser.parse_args()
    email = DEMO_EMAIL if args.slug == "deccan-traders" else f"owner@{args.slug}.test"
    api = Api()

    print(f"Creating workspace '{args.slug}' …")
    api.post("/auth/signup", {"workspaceName": "Deccan Traders", "slug": args.slug, "firstName": "Ibrahim",
                              "lastName": "Poonawala", "email": email, "password": DEMO_PASSWORD})
    api.post("/auth/verify-email", {"token": verification_token(email)})
    api.token = api.post("/auth/login", {"workspace": args.slug, "email": email,
                                         "password": DEMO_PASSWORD})["accessToken"]
    api.call("PATCH", "/tenant", {"currency": "INR", "timezone": "Asia/Kolkata", "locale": "en-IN"})
    for module in ("CRM", "INVENTORY"):
        api.put(f"/tenant/modules/{module}", {"enabled": True})

    print("Directory …")
    org = {}
    for name, domain in [("Konkan Supplies", "konkan.test"), ("Malabar Traders", "malabar.test"),
                         ("Sahyadri Stores", "sahyadri.test"), ("Pune Kitchenware Co", "punekitchen.test")]:
        org[name] = api.post("/organizations", {"name": name, "domain": domain})["id"]
    api.post("/persons", {"firstName": "Meera", "lastName": "Iyer", "jobTitle": "Purchase manager",
                          "organizationId": org["Sahyadri Stores"], "email": "meera@sahyadri.test"})
    api.post("/persons", {"firstName": "Arjun", "lastName": "Patil", "jobTitle": "Owner",
                          "organizationId": org["Pune Kitchenware Co"], "email": "arjun@punekitchen.test"})

    print("Products …")
    product = {}
    for sku, name, unit, price in [("BTL-1L", "Steel water bottle 1 L", "each", 349),
                                   ("TIF-3", "Three-tier tiffin box", "each", 599),
                                   ("KTL-2", "Pressure cooker 2 L", "each", 1499),
                                   ("SPN-25", "Kitchen sponge pack of 25", "pack", 199)]:
        product[sku] = api.post("/products", {"sku": sku, "name": name, "kind": "GOODS", "unit": unit,
                                              "listPrice": price, "currency": "INR"})["id"]
    api.post("/products", {"sku": "INST-1", "name": "Installation visit", "kind": "SERVICE", "unit": "visit",
                           "listPrice": 500, "currency": "INR"})

    print("CRM …")
    api.post("/leads", {"firstName": "Kavya", "lastName": "Nair", "companyName": "Kochi Home Mart",
                        "email": "kavya@kochihome.test", "source": "REFERRAL", "estimatedValue": 85000,
                        "currency": "INR"})
    api.post("/leads", {"firstName": "Rohan", "lastName": "Deshmukh", "companyName": "Nashik Utensils",
                        "source": "EVENT", "estimatedValue": 40000, "currency": "INR"})
    api.post("/leads", {"companyName": "Goa Hotel Supplies", "source": "WEBSITE", "estimatedValue": 120000,
                        "currency": "INR"})
    stages = api.get("/crm/pipeline/stages")
    opp = api.post("/opportunities", {"name": "Sahyadri festive order", "accountId": org["Sahyadri Stores"],
                                      "amount": 250000, "currency": "INR", "expectedCloseOn": "2026-11-15"})
    proposal = next((s for s in stages if s["kind"] == "OPEN" and s["probability"] >= 50), None)
    if proposal:
        api.post(f"/opportunities/{opp['id']}/stage", {"stageId": proposal["id"], "version": opp["version"]})
    won = api.post("/opportunities", {"name": "Pune Kitchenware annual contract",
                                      "accountId": org["Pune Kitchenware Co"], "amount": 180000,
                                      "currency": "INR"})
    won_stage = next(s for s in stages if s["kind"] == "WON")
    api.post(f"/opportunities/{won['id']}/stage", {"stageId": won_stage["id"], "version": won["version"]})

    print("Inventory …")
    warehouses = api.get("/inventory/warehouses")
    main_wh = next(w for w in warehouses if w["code"] == "MAIN")["id"]
    pune = api.post("/inventory/warehouses", {"code": "PUNE", "name": "Pune godown",
                                              "address": "Hadapsar, Pune"})["id"]
    for sku, qty in [("BTL-1L", 120), ("TIF-3", 60), ("KTL-2", 25), ("SPN-25", 300)]:
        api.post("/inventory/adjustments", {"productId": product[sku], "warehouseId": main_wh,
                                            "countedQuantity": qty, "reason": "Opening count"})
    api.post("/inventory/transfers", {"productId": product["BTL-1L"], "fromWarehouseId": main_wh,
                                      "toWarehouseId": pune, "quantity": 30, "note": "Stock for Pune orders"})
    rules = [("BTL-1L", 40, 150, "Konkan Supplies"), ("TIF-3", 30, 100, "Malabar Traders"),
             ("KTL-2", 10, 40, "Konkan Supplies")]
    for sku, lo, hi, supplier in rules:
        api.put("/inventory/reorder-rules", {"productId": product[sku], "warehouseId": main_wh,
                                             "minQuantity": lo, "maxQuantity": hi, "supplierId": org[supplier]})

    def sale(customer, lines, fulfil=True):
        so = api.post("/sales-orders", {"customerId": org[customer], "warehouseId": main_wh,
                                        "lines": [{"productId": product[s], "quantity": q} for s, q in lines]})
        so = api.post(f"/sales-orders/{so['id']}/confirm", {"version": so["version"]})
        if fulfil:
            api.post(f"/sales-orders/{so['id']}/fulfil", {"version": so["version"]})

    sale("Sahyadri Stores", [("BTL-1L", 55), ("TIF-3", 38)])
    sale("Pune Kitchenware Co", [("KTL-2", 18), ("SPN-25", 120)])
    sale("Sahyadri Stores", [("TIF-3", 6)], fulfil=False)

    # One earlier purchase order, received in two parts, so receipts and costs show up.
    po = api.post("/purchase-orders", {"supplierId": org["Konkan Supplies"], "warehouseId": main_wh,
                                       "expectedOn": "2026-10-20",
                                       "lines": [{"productId": product["SPN-25"], "quantity": 200, "unitCost": 120}]})
    po = api.post(f"/purchase-orders/{po['id']}/order", {"version": po["version"]})
    line = po["lines"][0]["id"]
    po = api.post(f"/purchase-orders/{po['id']}/receipts",
                  {"lines": [{"lineId": line, "quantity": 120}], "version": po["version"]})
    api.post(f"/purchase-orders/{po['id']}/receipts", {"lines": [{"lineId": line, "quantity": 80}],
                                                       "version": po["version"]})

    print("\nDone. Sign in at http://localhost:3000/login")
    print(f"  Workspace URL: {args.slug}")
    print(f"  Email:         {email}")
    print("  Password:      DEMO_PASSWORD in scripts/demo_seed.py")


if __name__ == "__main__":
    main()

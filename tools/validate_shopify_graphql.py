"""Do the app's GraphQL documents still match the live Shopify Admin API?

Extracts the text-block constants from `sync/ShopifyGraphQL.java` — so what gets checked is exactly
what the app sends — and runs each one against a real store with variables that point at ids which
cannot exist. A document the schema rejects comes back as a top-level `errors` entry (that is the
failure this catches); a valid one comes back with a `userErrors` "does not exist", which changes
nothing in the store. An "Access denied" answer is reported as NO SCOPE, not as a failure: the
document is fine, the app is simply missing that scope. Run it after editing a query and before
bumping `shopify.api-version`.

Use it when:
  * a query or mutation in ShopifyGraphQL.java changed
  * the API version is about to move and the deprecations are unknown

Usage:
    set SHOPIFY_TOKEN=shpat_...        (or pass --token; get one with the client_credentials grant)
    python tools/validate_shopify_graphql.py --store trophy-partner.myshopify.com
    python tools/validate_shopify_graphql.py --store ... --api-version 2026-07 --only VARIANTS_BULK_CREATE

Requires: standard library only.
Origin: 2026-08-28, adding variant creation for migrated products.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_SOURCE = Path(__file__).resolve().parents[1] / (
    "src/main/java/com/trophy/promostandards/sync/ShopifyGraphQL.java")

GHOST = {
    "product": "gid://shopify/Product/999999999999",
    "variant": "gid://shopify/ProductVariant/999999999999",
    "option": "gid://shopify/ProductOption/999999999999",
    "value": "gid://shopify/ProductOptionValue/999999999999",
    "item": "gid://shopify/InventoryItem/999999999999",
    "location": "gid://shopify/Location/999999999999",
    "order": "gid://shopify/Order/999999999999",
}

VARIANT_METAFIELD = [{"namespace": "custom", "key": "promo_standard_id",
                      "type": "single_line_text_field", "value": "GI307"}]

# One set of variables per constant. A constant with no entry here is reported as unchecked rather
# than silently skipped — a new query should get a case, not a pass.
CASES: dict[str, dict] = {
    "PRODUCT_BY_HANDLE": {"query": "handle:ps-pacesetter-does-not-exist",
                          "locationId": GHOST["location"], "withLocation": True},
    "PRODUCT_SET": {"input": {"handle": "ps-pacesetter-does-not-exist", "title": "probe",
                              "productOptions": [{"name": "Color", "position": 1,
                                                  "values": [{"name": "Clear"}]}],
                              "variants": [{"optionValues": [{"optionName": "Color", "name": "Clear"}],
                                            "inventoryItem": {"sku": "PROBE", "tracked": True}}]},
                    "synchronous": False},
    "INVENTORY_SET_QUANTITIES": {"input": {"name": "available", "reason": "correction",
                                           "quantities": [{"inventoryItemId": GHOST["item"],
                                                           "locationId": GHOST["location"],
                                                           "quantity": 1,
                                                           "changeFromQuantity": 0}]},
                                 "idempotencyKey": "00000000-0000-4000-8000-000000000000"},
    "VARIANTS_BULK_UPDATE": {"productId": GHOST["product"],
                             "variants": [{"id": GHOST["variant"], "price": "1.00",
                                           "inventoryItem": {"sku": "PROBE", "tracked": True},
                                           "optionValues": [{"optionName": "Color", "name": "Clear"}],
                                           "metafields": VARIANT_METAFIELD}]},
    "VARIANTS_BULK_CREATE": {"productId": GHOST["product"],
                             "strategy": "PRESERVE_STANDALONE_VARIANT",
                             "variants": [{"optionValues": [{"optionName": "Color", "name": "Clear"}],
                                           "price": "1.00",
                                           "inventoryItem": {"sku": "PROBE", "tracked": True},
                                           "metafields": VARIANT_METAFIELD,
                                           "inventoryQuantities": [
                                               {"locationId": GHOST["location"],
                                                "availableQuantity": 1}]}]},
    "PRODUCT_OPTION_UPDATE": {"productId": GHOST["product"],
                              "option": {"id": GHOST["option"], "name": "Color"},
                              "optionValuesToUpdate": [{"id": GHOST["value"], "name": "Clear"}],
                              "variantStrategy": "LEAVE_AS_IS"},
    "PRODUCT_OPTIONS_CREATE": {"productId": GHOST["product"],
                               "options": [{"name": "Size", "position": 2,
                                            "values": [{"name": "One Size"}]}],
                               "variantStrategy": "LEAVE_AS_IS"},
    "INVENTORY_ACTIVATE": {"inventoryItemId": GHOST["item"],
                           "updates": [{"locationId": GHOST["location"], "activate": True}]},
    "LOCATIONS": {},
    "METAFIELD_DEFINITIONS": {},
    "METAFIELD_SAMPLES": {"limit": 1, "namespace": "custom", "key": "ps_product_id"},
    "METAOBJECT_BY_HANDLE": {"handle": {"type": "promo_standard_supplier", "handle": "nope"}},
    "METAFIELD_DEFINITION_CREATE": {"definition": {"name": "probe", "namespace": "custom",
                                                   "key": "ps_probe_does_not_exist",
                                                   "type": "single_line_text_field",
                                                   "ownerType": "PRODUCT"}},
    "METAFIELD_DEFINITION_UPDATE": {"definition": {"namespace": "custom",
                                                   "key": "ps_probe_does_not_exist",
                                                   "ownerType": "PRODUCT"}},
    "ORDER_BY_ID": {"id": GHOST["order"]},
    "ORDER_BY_PO": {"query": "name:does-not-exist"},
    "FULFILLMENT_CREATE": {"fulfillment": {"lineItemsByFulfillmentOrder": [
        {"fulfillmentOrderId": "gid://shopify/FulfillmentOrder/999999999999"}]}},
    "METAFIELDS_SET": {"metafields": [{"ownerId": GHOST["product"], "namespace": "custom",
                                       "key": "ps_last_sync_at", "type": "date_time",
                                       "value": "2026-01-01T00:00:00Z"}]},
    "IMPORTED_PRODUCTS": {"discountNamespace": "trophy_discount", "discountKey": "discount_tiers"},
    # Media: all three run against ids that cannot exist, so nothing is added or deleted for real.
    "PRODUCT_ADD_MEDIA": {"id": GHOST["product"],
                          "media": [{"originalSource": "https://example.invalid/probe.jpg",
                                     "mediaContentType": "IMAGE", "alt": "probe"}]},
    "FILE_DELETE": {"fileIds": ["gid://shopify/MediaImage/999999999999"]},
    "VARIANT_APPEND_MEDIA": {"productId": GHOST["product"],
                             "variantMedia": [{"variantId": GHOST["variant"],
                                               "mediaIds": ["gid://shopify/MediaImage/999999999999"]}]},
}

# Mutations that would really write if the ghost ids ever resolved. Skipped unless --include-writes:
# METAFIELD_DEFINITION_CREATE actually creates a definition, so it is opt-in.
WRITES_FOR_REAL = {"METAFIELD_DEFINITION_CREATE", "PRODUCT_SET"}


def constants(source: Path) -> dict[str, str]:
    text = source.read_text(encoding="utf-8")
    return {m.group(1): m.group(2)
            for m in re.finditer(r'static final String (\w+) = """\n(.*?)\n\s*""";', text, re.S)}


def call(url: str, token: str, query: str, variables: dict) -> dict:
    payload = json.dumps({"query": query, "variables": variables}).encode()
    request = urllib.request.Request(url, data=payload, method="POST")
    request.add_header("Content-Type", "application/json")
    request.add_header("X-Shopify-Access-Token", token)
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--store", required=True, help="myshopify domain, e.g. shop.myshopify.com")
    parser.add_argument("--api-version", default="2026-04")
    parser.add_argument("--token", default=os.environ.get("SHOPIFY_TOKEN"),
                        help="Admin API access token (default: $SHOPIFY_TOKEN)")
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--only", action="append", default=[], help="check only these constants")
    parser.add_argument("--include-writes", action="store_true",
                        help="also check the documents that would really write something")
    args = parser.parse_args()

    if not args.token:
        parser.error("no token: pass --token or set SHOPIFY_TOKEN")

    url = f"https://{args.store}/admin/api/{args.api_version}/graphql.json"
    documents = constants(args.source)
    if not documents:
        print(f"no GraphQL constants found in {args.source}", file=sys.stderr)
        return 2

    failures = 0
    for name, document in documents.items():
        if args.only and name not in args.only:
            continue
        if name not in CASES:
            print(f"UNCHECKED {name}  (add variables to CASES in {Path(__file__).name})")
            failures += 1
            continue
        if name in WRITES_FOR_REAL and not args.include_writes:
            print(f"SKIPPED   {name}  (writes for real; --include-writes to check)")
            continue
        try:
            result = call(url, args.token, document, CASES[name])
        except urllib.error.HTTPError as error:
            print(f"HTTP {error.code} {name}: {error.read()[:200]!r}")
            failures += 1
            continue
        errors = result.get("errors") or []
        if errors and all("Access denied" in str(e.get("message")) for e in errors):
            # A scope the app was never granted; the document itself is fine.
            print(f"NO SCOPE  {name}  | {str(errors[0].get('message'))[:120]}")
        elif errors:
            failures += 1
            print(f"INVALID   {name}")
            for error in errors:
                print("             " + str(error.get("message"))[:200])
        else:
            payload = next(iter((result.get("data") or {}).values()), None)
            user_errors = payload.get("userErrors") if isinstance(payload, dict) else None
            detail = json.dumps(user_errors)[:120] if user_errors else "none"
            print(f"OK        {name}  | userErrors: {detail}")

    print(f"\n{'FAILED' if failures else 'PASSED'}: {failures} problem(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

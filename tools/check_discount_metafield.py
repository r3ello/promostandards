"""Can this app read (and therefore hope to write) the discount app's quantity-break metafield?

The store's discount app owns `app--<app id>.discount_tiers`, and `app--<app id>` is Shopify's
RESERVED prefix for an app's own namespace: by default only the owning app touches it, and another
app's write comes back as a userError rather than a 403. This script answers, read-only, the three
questions that decide whether the sync can publish there at all:

  1. Is there a metafield DEFINITION for it (product and variant), and what type and access does it
     declare? `access.admin` of PUBLIC_READ means other apps may read it; anything that is not
     read-write for us means our writes will be refused.
  2. Do real products already carry a value, and what does that value look like? That is the ground
     truth for the payload shape - worth more than any documentation.
  3. Does OUR token see it at all? A definition that exists but reads back null on every product is
     the signature of a namespace we are locked out of.

Nothing here writes: only `metafieldDefinitions` and `products` queries. Reads against the live store
are safe, which is exactly why this check is a script and not a unit test.

Usage:
    python tools/check_discount_metafield.py --store trophy-partner.myshopify.com
    python tools/check_discount_metafield.py --namespace trophy_discount --key discount_tiers

Credentials: SHOPIFY_TOKEN if set, else the client_credentials grant using the client-id/secret from
src/main/resources/application-local.yaml (the same one the app uses).

Requires: standard library only.
Origin: 2026-09-03, moving quantity discounts from the Orichi API to a Shopify metafield.
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

LOCAL_YAML = Path(__file__).resolve().parents[1] / "src/main/resources/application-local.yaml"

DEFINITIONS = """
query DiscountDefinitions($ownerType: MetafieldOwnerType!, $namespace: String!) {
  metafieldDefinitions(first: 10, ownerType: $ownerType, namespace: $namespace) {
    nodes {
      key
      name
      type { name }
      ownerType
      access { admin storefront }
    }
  }
}
"""

# Same shape the app's own IMPORTED_PRODUCTS uses, plus the variant side and a raw dump of every
# metafield namespace on the product - which is how a namespace we cannot see makes itself known.
# The search term is INLINED, not a variable: Shopify answers `query: $query` with an empty page for
# a tag search that the identical literal matches (verified 2026-09-03 - the variable form reported
# zero tagged products in a store holding hundreds). The app's own IMPORTED_PRODUCTS inlines it too.
PRODUCTS = """
query TaggedProducts($namespace: String!, $key: String!) {
  products(first: 50, query: "%s") {
    nodes {
      handle
      psId: metafield(namespace: "custom", key: "ps_product_id") { value }
      discounts: metafield(namespace: $namespace, key: $key) { value type }
      metafields(first: 25) { nodes { namespace key type } }
      variants(first: 5) {
        nodes {
          sku
          discounts: metafield(namespace: $namespace, key: $key) { value type }
        }
      }
    }
  }
}
"""


def local_credentials() -> tuple[str, str, str, str]:
    """(store, client_id, client_secret, api_version) from application-local.yaml."""
    if not LOCAL_YAML.exists():
        sys.exit(f"no {LOCAL_YAML}; pass --token and --store instead")
    block = LOCAL_YAML.read_text(encoding="utf-8")
    block = block[block.index("\nshopify:"):]

    def value(name: str, default: str = "") -> str:
        match = re.search(rf"^\s+{name}:\s*\"?([^\"\n]+)\"?", block, re.MULTILINE)
        return match.group(1).strip() if match else default

    return value("store-domain"), value("client-id"), value("client-secret"), value("api-version", "2026-04")


def access_token(store: str, client_id: str, client_secret: str) -> str:
    body = json.dumps({
        "client_id": client_id,
        "client_secret": client_secret,
        "grant_type": "client_credentials",
    }).encode()
    request = urllib.request.Request(
        f"https://{store}/admin/oauth/access_token", data=body,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)["access_token"]


def graphql(store: str, version: str, token: str, query: str, variables: dict) -> dict:
    request = urllib.request.Request(
        f"https://{store}/admin/api/{version}/graphql.json",
        data=json.dumps({"query": query, "variables": variables}).encode(),
        headers={"Content-Type": "application/json", "X-Shopify-Access-Token": token})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:  # 402/403/423 carry a readable body
        return {"errors": [{"message": f"HTTP {e.code}: {e.read().decode(errors='replace')[:400]}"}]}


def report_errors(label: str, payload: dict) -> bool:
    for error in payload.get("errors") or []:
        print(f"  ! {label}: {error.get('message')}")
    return not payload.get("errors")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--store")
    parser.add_argument("--token", default=os.environ.get("SHOPIFY_TOKEN"))
    parser.add_argument("--api-version")
    parser.add_argument("--namespace", default="trophy_discount")
    parser.add_argument("--key", default="discount_tiers")
    parser.add_argument("--query", default="tag:promostandards",
                        help="which products to sample (default: the ones this app manages)")
    args = parser.parse_args()

    store, client_id, client_secret, version = local_credentials()
    store = args.store or store
    version = args.api_version or version
    token = args.token or access_token(store, client_id, client_secret)

    print(f"store {store} · API {version} · metafield {args.namespace}.{args.key}\n")

    print("1. Metafield definitions")
    defined = False
    for owner in ("PRODUCT", "PRODUCTVARIANT"):
        payload = graphql(store, version, token, DEFINITIONS,
                          {"ownerType": owner, "namespace": args.namespace})
        if not report_errors(f"{owner} definitions", payload):
            continue
        nodes = payload["data"]["metafieldDefinitions"]["nodes"]
        if not nodes:
            print(f"  {owner}: no definition in this namespace (visible to us)")
        for node in nodes:
            defined = True
            print(f"  {owner}: {node['key']} · type={node['type']['name']} · "
                  f"access={node.get('access')} · name={node.get('name')!r}")

    print("\n2. Values on real products")
    payload = graphql(store, version, token, PRODUCTS % args.query.replace('"', '\\"'),
                      {"namespace": args.namespace, "key": args.key})
    seen_value = False
    if report_errors("products", payload):
        nodes = payload["data"]["products"]["nodes"]
        if not nodes:
            print(f"  no product matched {args.query!r}")
        for node in nodes:
            product_value = (node.get("discounts") or {}).get("value")
            variant_values = [(v["sku"], (v.get("discounts") or {}).get("value"))
                              for v in node["variants"]["nodes"]]
            namespaces = sorted({m["namespace"] for m in node["metafields"]["nodes"]})
            seen_value = seen_value or bool(product_value) or any(v for _, v in variant_values)
            print(f"  {node['handle']} (ps={(node.get('psId') or {}).get('value')})")
            print(f"      product: {product_value!r}")
            for sku, value in variant_values:
                if value:
                    print(f"      variant {sku}: {value!r}")
            print(f"      namespaces visible on this product: {namespaces}")

    print("\nVerdict")
    if seen_value:
        print("  we CAN read the metafield and there is real data in it — compare the shape above")
        print("  with QuantityDiscountJson before publishing.")
    elif defined:
        print("  the definition is visible but no sampled product carries a value: either nothing is")
        print("  published yet, or the value is hidden from this app. Try --query on a product you")
        print("  know has discounts in the storefront.")
    else:
        print("  nothing visible. If the discount app owns this reserved namespace and grants us no")
        print("  access, writes will be refused: ask for access, or for a merchant-owned namespace.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

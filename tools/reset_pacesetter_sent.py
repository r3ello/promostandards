"""Take the "sent to PaceSetter" mark off an order, so the same order can be sent again as if new.

The app marks an order it emailed with the tag `pacesetter-enviado` (what the pending list filters
on) and the metafield `trophy_sync.pacesetter_po` (the record of what went). Testing the send on one
order means removing both after every try; this does it, and nothing else.

Dry run by default: it shows the mark and changes nothing until `--apply`. It refuses a store other
than the one in application-local.yaml unless `--store` names it, because on the live store the
mark is the only thing that stops a PO being emailed twice.

Usage:
    python tools/reset_pacesetter_sent.py 1049              # what would be removed
    python tools/reset_pacesetter_sent.py 1049 --apply      # remove it
    python tools/reset_pacesetter_sent.py 7300090560606 --apply   # numeric id or order GID also work

Needs write_orders. Credentials: the client_credentials grant from application-local.yaml.

Requires: standard library only.
Origin: 2026-09-27, repeated send tests on order #1049 of trophy-partner-dev.
"""

from __future__ import annotations

import argparse
import sys

from check_discount_metafield import access_token, graphql, local_credentials, report_errors

TAG = "pacesetter-enviado"
NAMESPACE, KEY = "trophy_sync", "pacesetter_po"

FIND = """
query FindOrder($q: String!) {
  orders(first: 1, query: $q) { nodes { id name tags sent: metafield(namespace: "%s", key: "%s") { value } } }
}
""" % (NAMESPACE, KEY)

BY_ID = """
query OrderById($id: ID!) {
  order(id: $id) { id name tags sent: metafield(namespace: "%s", key: "%s") { value } }
}
""" % (NAMESPACE, KEY)

DELETE = """
mutation MetafieldsDelete($metafields: [MetafieldIdentifierInput!]!) {
  metafieldsDelete(metafields: $metafields) { deletedMetafields { key } userErrors { field message } }
}
"""

UNTAG = """
mutation TagsRemove($id: ID!, $tags: [String!]!) {
  tagsRemove(id: $id, tags: $tags) { node { id } userErrors { field message } }
}
"""


def find(store: str, version: str, token: str, ref: str) -> dict | None:
    ref = ref.strip().lstrip("#")
    if ref.startswith("gid://") or len(ref) > 9:
        gid = ref if ref.startswith("gid://") else f"gid://shopify/Order/{ref}"
        payload = graphql(store, version, token, BY_ID, {"id": gid})
        report_errors("order", payload)
        return (payload.get("data") or {}).get("order")
    payload = graphql(store, version, token, FIND, {"q": f"name:#{ref}"})
    report_errors("orders", payload)
    nodes = ((payload.get("data") or {}).get("orders") or {}).get("nodes") or []
    return nodes[0] if nodes else None


def user_errors(label: str, payload: dict, field: str) -> bool:
    ok = report_errors(label, payload)
    for e in ((payload.get("data") or {}).get(field) or {}).get("userErrors") or []:
        print(f"  ! {label}: {e.get('message')}")
        ok = False
    return ok


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("order", help="order number (1049), numeric id or order GID")
    parser.add_argument("--apply", action="store_true", help="remove the mark (default: only show it)")
    parser.add_argument("--store", help="a store other than application-local.yaml's; required to touch it")
    args = parser.parse_args()

    store, client_id, secret, version = local_credentials()
    if args.store and args.store != store:
        print(f"refusing: {args.store} is not the configured store {store}; these credentials are for that one")
        return 2
    token = access_token(store, client_id, secret)

    order = find(store, version, token, args.order)
    if not order:
        print(f"no order {args.order} on {store}")
        return 1
    tagged = any(t.lower() == TAG for t in order.get("tags") or [])
    sent = (order.get("sent") or {}).get("value")
    print(f"{order['name']} on {store} ({order['id']})")
    print(f"  tag {TAG}: {'yes' if tagged else 'no'}")
    print(f"  metafield {NAMESPACE}.{KEY}: {sent if sent else 'none'}")
    if not tagged and not sent:
        print("  nothing to remove: the order can be sent as it is")
        return 0
    if not args.apply:
        print("  dry run: add --apply to remove the mark")
        return 0

    ok = True
    if sent:
        ok &= user_errors("metafieldsDelete", graphql(store, version, token, DELETE, {"metafields": [
            {"ownerId": order["id"], "namespace": NAMESPACE, "key": KEY}]}), "metafieldsDelete")
    if tagged:
        ok &= user_errors("tagsRemove", graphql(store, version, token, UNTAG,
                                                {"id": order["id"], "tags": [TAG]}), "tagsRemove")
    print("  removed: the order can be sent again" if ok else "  not everything was removed (see above)")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

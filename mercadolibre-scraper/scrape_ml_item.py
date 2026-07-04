#!/usr/bin/env python3
"""Scrape a MercadoLibre Mexico listing: tab info (description, characteristics) and images.

Usage:
    python3 scrape_ml_item.py MLM2075842359 [--out data/]

For each item the script tries, in order:
  1. The public items API (api.mercadolibre.com) for structured data.
  2. The product page HTML, parsing the JSON-LD block and preloaded state
     for title, price, attributes, description and picture URLs.

Output layout:
    <out>/<ITEM_ID>/product.json   structured data (tabs + metadata)
    <out>/<ITEM_ID>/product.md     human-readable summary
    <out>/<ITEM_ID>/images/        full-resolution product photos
"""

import argparse
import html
import json
import re
import sys
from pathlib import Path

import requests

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    ),
    "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
}
API_BASE = "https://api.mercadolibre.com"
TIMEOUT = 30


def normalize_item_id(raw: str) -> str:
    item_id = raw.strip().upper().replace("-", "")
    if not re.fullmatch(r"MLM\d+", item_id):
        sys.exit(f"'{raw}' does not look like a MercadoLibre Mexico item ID (MLM...)")
    return item_id


def fetch_api(item_id: str) -> dict | None:
    """Try the public items API; returns merged item + description or None."""
    try:
        resp = requests.get(f"{API_BASE}/items/{item_id}", headers=HEADERS, timeout=TIMEOUT)
        if resp.status_code != 200:
            print(f"  API items/{item_id} -> HTTP {resp.status_code}")
            return None
        item = resp.json()
        desc = requests.get(
            f"{API_BASE}/items/{item_id}/description", headers=HEADERS, timeout=TIMEOUT
        )
        item["_description_text"] = (
            desc.json().get("plain_text", "") if desc.status_code == 200 else ""
        )
        return item
    except requests.RequestException as exc:
        print(f"  API request failed: {exc}")
        return None


def fetch_page(item_id: str) -> str | None:
    """Fetch the listing HTML, trying both URL styles."""
    dashed = f"{item_id[:3]}-{item_id[3:]}"
    for url in (
        f"https://articulo.mercadolibre.com.mx/{dashed}-_JM",
        f"https://www.mercadolibre.com.mx/p/{item_id}",
    ):
        try:
            resp = requests.get(url, headers=HEADERS, timeout=TIMEOUT, allow_redirects=True)
            print(f"  GET {url} -> HTTP {resp.status_code}")
            if resp.status_code == 200 and "mercadolibre" in resp.url:
                return resp.text
        except requests.RequestException as exc:
            print(f"  GET {url} failed: {exc}")
    return None


def parse_page(html_text: str) -> dict:
    """Extract tab data (description, characteristics) and images from page HTML."""
    data: dict = {"attributes": {}, "pictures": [], "description": ""}

    for block in re.findall(
        r'<script type="application/ld\+json">(.*?)</script>', html_text, re.S
    ):
        try:
            ld = json.loads(block)
        except json.JSONDecodeError:
            continue
        if ld.get("@type") == "Product":
            offer = ld.get("offers", {}) or {}
            rating = ld.get("aggregateRating", {}) or {}
            data.update(
                title=ld.get("name"),
                description=ld.get("description", ""),
                price=offer.get("price"),
                currency=offer.get("priceCurrency"),
                condition=offer.get("itemCondition"),
                availability=offer.get("availability"),
                seller=(offer.get("seller") or {}).get("name"),
                rating=rating.get("ratingValue"),
                review_count=rating.get("reviewCount"),
            )
            images = ld.get("image")
            if images:
                data["pictures"] = images if isinstance(images, list) else [images]

    # Characteristics tab: rendered as th/td rows in the specs table.
    for key, value in re.findall(
        r'<th[^>]*>([^<]+)</th>\s*<td[^>]*>(?:<span[^>]*>)?([^<]+)', html_text
    ):
        data["attributes"][html.unescape(key).strip()] = html.unescape(value).strip()

    # Gallery images: full-resolution D_NQ_NP picture IDs on mlstatic.
    seen = set(data["pictures"])
    for pic in re.findall(r'https://http2\.mlstatic\.com/D_NQ_NP_(?:2X_)?[\w.-]+\.(?:webp|jpg|png)', html_text):
        full = re.sub(r"D_NQ_NP_", "D_NQ_NP_2X_", pic) if "2X" not in pic else pic
        if full not in seen:
            seen.add(full)
            data["pictures"].append(full)

    return data


def api_to_data(item: dict) -> dict:
    return {
        "title": item.get("title"),
        "price": item.get("price"),
        "original_price": item.get("original_price"),
        "currency": item.get("currency_id"),
        "condition": item.get("condition"),
        "sold_quantity": item.get("sold_quantity"),
        "available_quantity": item.get("available_quantity"),
        "permalink": item.get("permalink"),
        "seller_id": item.get("seller_id"),
        "free_shipping": (item.get("shipping") or {}).get("free_shipping"),
        "attributes": {
            a.get("name"): a.get("value_name")
            for a in item.get("attributes", [])
            if a.get("value_name")
        },
        "pictures": [p.get("secure_url") or p.get("url") for p in item.get("pictures", [])],
        "description": item.get("_description_text", ""),
    }


def download_images(urls: list[str], dest: Path) -> list[str]:
    dest.mkdir(parents=True, exist_ok=True)
    saved = []
    for i, url in enumerate(urls, 1):
        ext = Path(url.split("?")[0]).suffix or ".jpg"
        path = dest / f"{i:02d}{ext}"
        try:
            resp = requests.get(url, headers=HEADERS, timeout=TIMEOUT)
            resp.raise_for_status()
            path.write_bytes(resp.content)
            saved.append(path.name)
            print(f"  saved {path.name} ({len(resp.content)} bytes)")
        except requests.RequestException as exc:
            print(f"  image {url} failed: {exc}")
    return saved


def write_markdown(data: dict, path: Path) -> None:
    lines = [f"# {data.get('title') or 'Unknown item'}", ""]
    price = data.get("price")
    if price:
        lines.append(f"**Price:** {price} {data.get('currency') or ''}".rstrip())
    for label, key in [
        ("Condition", "condition"),
        ("Sold", "sold_quantity"),
        ("Rating", "rating"),
        ("Reviews", "review_count"),
        ("Seller", "seller"),
        ("Link", "permalink"),
    ]:
        if data.get(key) is not None:
            lines.append(f"**{label}:** {data[key]}")
    if data.get("attributes"):
        lines += ["", "## Características", ""]
        lines += [f"- **{k}:** {v}" for k, v in data["attributes"].items()]
    if data.get("description"):
        lines += ["", "## Descripción", "", data["description"]]
    if data.get("pictures"):
        lines += ["", "## Imágenes", ""]
        lines += [f"- {u}" for u in data["pictures"]]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("item_id", help="MercadoLibre item ID, e.g. MLM2075842359")
    parser.add_argument("--out", default=str(Path(__file__).parent / "data"))
    args = parser.parse_args()

    item_id = normalize_item_id(args.item_id)
    out_dir = Path(args.out) / item_id
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"Scraping {item_id} ...")
    print("Trying public API:")
    item = fetch_api(item_id)
    if item:
        data = api_to_data(item)
    else:
        print("Falling back to product page:")
        page = fetch_page(item_id)
        if not page:
            sys.exit(
                "Could not reach MercadoLibre via API or page. "
                "Check network access to *.mercadolibre.com.mx / api.mercadolibre.com."
            )
        data = parse_page(page)

    data["item_id"] = item_id
    (out_dir / "product.json").write_text(
        json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    write_markdown(data, out_dir / "product.md")
    print(f"Wrote {out_dir / 'product.json'} and product.md")

    if data.get("pictures"):
        print(f"Downloading {len(data['pictures'])} images:")
        data["downloaded_images"] = download_images(data["pictures"], out_dir / "images")
        (out_dir / "product.json").write_text(
            json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    else:
        print("No image URLs found.")


if __name__ == "__main__":
    main()

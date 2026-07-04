# MercadoLibre SKU scraper

Scrapes a MercadoLibre Mexico listing — the info shown on the product page
tabs (descripción, características), pricing/seller metadata, and all
product images — and saves everything under `data/<ITEM_ID>/`.

## Usage

```bash
python3 scrape_ml_item.py MLM2075842359
```

Requires Python 3.10+ and `requests` (`pip install requests`).

## Output

```
data/MLM2075842359/
├── product.json   # structured data: title, price, attributes, description, image URLs
├── product.md     # human-readable summary
└── images/        # full-resolution product photos (01.webp, 02.webp, ...)
```

## How it works

1. Tries the public items API (`api.mercadolibre.com/items/<id>` +
   `/description`) for structured data.
2. Falls back to the product page HTML, parsing the JSON-LD `Product`
   block, the características table, and `http2.mlstatic.com` gallery URLs.
3. Downloads every picture at full resolution into `images/`.

## Network requirements

Outbound HTTPS to `api.mercadolibre.com`, `*.mercadolibre.com.mx` and
`http2.mlstatic.com`. Note: the Claude Code remote environment's default
network policy blocks these hosts; the committed `data/MLM2075842359/`
snapshot was assembled from search-engine data and is missing the
características tab and images until the script is run from an
unrestricted network.

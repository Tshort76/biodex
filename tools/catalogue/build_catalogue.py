#!/usr/bin/env python3
"""Build the bundled Pacific USA catalogue asset from the curated species lists.

Reads three hand-authored inputs — `region.json` (header + the seven ecosystems),
`curated_animals.json` (120 animals) and `curated_plants.json` (80 plants) —
enriches each entry from GBIF, Wikipedia/Wikimedia Commons, Xeno-canto (animals
only) and Dr. Duke's ethnobotanical database (plants only), and writes
`app/src/main/assets/catalogue/pacific.json` in the shape ARCHITECTURE.md
sections 3.2 and 11.1 specify, plus `duke_ethnobot.json` beside it.

Usage:
    python3 build_catalogue.py                       # default --out path
    python3 build_catalogue.py --out /some/where.json
    python3 build_catalogue.py --refresh             # ignore the cache
    python3 build_catalogue.py --plants /tmp/x.json  # swap an input file
    XC_API_KEY=... python3 build_catalogue.py        # also fetch calls

Standard library only (no `requests` on this machine).  Every HTTP response is
cached under `cache/`, so a re-run makes zero requests.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import csv
import io
import time
import unicodedata
import zipfile
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE_DIR = HERE / "cache"
DUKE_CACHE_DIR = CACHE_DIR / "duke"
ASSET_DIR = HERE / ".." / ".." / "app" / "src" / "main" / "assets" / "catalogue"
DEFAULT_OUT = ASSET_DIR / "pacific.json"

USER_AGENT = "BioDex/1.0 (personal Android app; tlong@unified.health)"

# Dr. Duke's Phytochemical and Ethnobotanical Databases (USDA ARS), CC0.
# The data.gov and Ag Data Commons landing pages 403 a plain fetch; the figshare
# API resolves the real download URL, so that is the route the script takes.
DUKE_ARTICLE_API = "https://api.figshare.com/v2/articles/24660351/files"
DUKE_ZIP_NAME = "Duke-Source-CSV.zip"
DUKE_ETHNOBOT_CSV = "ETHNOBOT.csv"
DUKE_ATTRIBUTION = "Dr. Duke's Phytochemical and Ethnobotanical Databases · USDA ARS · CC0"

# 11.2: the one rule the pipeline and the app's DukeIndex both implement.
MEDICINAL_ACTIVITY_THRESHOLD = 3
MEDICINAL_ACTIVITY_CAP = 8

PLANT_CLASSES = ("tree", "shrub", "herb", "fern")
USES_NOTE_MAX_CHARS = 240

# Politeness delays, per ARCHITECTURE.md 7.2.
DELAY_GBIF = 0.5
DELAY_WIKI = 1.0
DELAY_XC = 4.0

# GBIF class -> the app's taxClass enum (ARCHITECTURE.md 7.2).
CLASS_MAP = {
    "aves": "bird",
    "mammalia": "mammal",
    "reptilia": "reptile",
    "squamata": "reptile",
    "testudines": "reptile",
    "amphibia": "amphibian",
    "insecta": "insect",
    "actinopterygii": "fish",
    "actinopteri": "fish",
    "teleostei": "fish",
    "chondrichthyes": "fish",
    "elasmobranchii": "fish",
}

# Counters the report reads.
HTTP_REQUESTS = 0
CACHE_HITS = 0


# --------------------------------------------------------------------------
# HTTP with an on-disk cache
# --------------------------------------------------------------------------

def _scrub(url: str) -> str:
    """Strip the Xeno-canto key so it never reaches a log or the report."""
    return re.sub(r"([?&]key=)[^&]*", r"\1<redacted>", url)


def fetch_json(url: str, *, refresh: bool = False, delay: float = 0.0):
    """GET `url` and parse JSON, going through the disk cache.

    Returns (payload, from_cache).  A payload of None means the request failed
    or returned a 404; failures are cached too, so a re-run stays at zero
    requests.
    """
    global HTTP_REQUESTS, CACHE_HITS
    key = hashlib.sha1(url.encode("utf-8")).hexdigest()
    path = CACHE_DIR / f"{key}.json"

    if path.exists() and not refresh:
        CACHE_HITS += 1
        with path.open(encoding="utf-8") as fh:
            return json.load(fh).get("payload"), True

    if delay:
        time.sleep(delay)
    HTTP_REQUESTS += 1
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    payload = None
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        # A 404 is a real answer ("no such page") and is worth caching. Every other
        # HTTP status is a transient server condition — a 429 or a 503 cached as
        # "no match" would silently poison every later run, including the GBIF
        # kingdom check, so it is treated like a network hiccup and not written.
        if exc.code != 404:
            print(f"    ! HTTP {exc.code} for {_scrub(url)}", file=sys.stderr)
            return None, False
    except Exception as exc:  # network hiccup, bad JSON, timeout
        print(f"    ! {type(exc).__name__} for {_scrub(url)}: {exc}", file=sys.stderr)
        # Do not cache a transient failure — let the next run retry it.
        return None, False

    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        json.dump({"url": _scrub(url), "payload": payload}, fh)
    return payload, False


def fetch_bytes(url: str, dest: Path, *, refresh: bool = False) -> bytes:
    """GET `url` as bytes into `dest`, reusing the file on a re-run.

    Used for the one large binary the pipeline needs (Duke's 5.8 MB zip), which
    the sha1-keyed JSON cache is the wrong shape for.
    """
    global HTTP_REQUESTS, CACHE_HITS
    if dest.exists() and not refresh:
        CACHE_HITS += 1
        return dest.read_bytes()
    HTTP_REQUESTS += 1
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=180) as resp:
        data = resp.read()
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)
    return data


# --------------------------------------------------------------------------
# GBIF
# --------------------------------------------------------------------------

def gbif_match(scientific_name: str, refresh: bool):
    url = "https://api.gbif.org/v1/species/match?strict=false&name=" + urllib.parse.quote(scientific_name)
    return fetch_json(url, refresh=refresh, delay=DELAY_GBIF)[0]


def tax_class_for(gbif_class: str | None, phylum: str | None = None) -> str:
    """Map GBIF's class to the app's taxClass enum.

    GBIF's backbone carries no `class` for ray-finned fishes — the old
    Actinopterygii node is gone and salmon come back with an order
    (Salmoniformes) and nothing above it but the phylum. Birds, mammals,
    reptiles and amphibians all still carry a class, so "a chordate with no
    class" is reliably a fish. Sharks and rays do carry Chondrichthyes /
    Elasmobranchii and are handled by the table.
    """
    if gbif_class:
        mapped = CLASS_MAP.get(gbif_class.strip().lower())
        if mapped:
            return mapped
        return "other_invertebrate"
    if (phylum or "").strip().lower() == "chordata":
        return "fish"
    return "other_invertebrate"


def gbif_synonyms(usage_key, refresh: bool):
    """The accepted taxon's synonyms, as `Genus species` binomials.

    R15: Duke's keys on genus and species strings from its own era, so an
    accepted name GBIF has since moved (Oregon grape *Berberis* / *Mahonia*)
    misses on the first try. This is one extra cached request per plant.
    """
    if not usage_key:
        return []
    url = f"https://api.gbif.org/v1/species/{usage_key}/synonyms?limit=100"
    payload = fetch_json(url, refresh=refresh, delay=DELAY_GBIF)[0] or {}
    names = []
    for result in payload.get("results", []):
        # `species` is the binomial with any infraspecific epithet dropped, which
        # is the level Duke's files things at; canonicalName is the fallback.
        name = result.get("species") or result.get("canonicalName")
        if not name:
            continue
        parts = name.split()
        if len(parts) >= 2:
            binomial = f"{parts[0]} {parts[1]}"
            if binomial not in names:
                names.append(binomial)
    return names


def plant_silhouette(plant_class: str, gbif_class, gbif_order) -> str:
    """The one automated decision that leans on GBIF's plant taxonomy (R10).

    GBIF is inconsistent about conifers — sometimes class Pinopsida, sometimes
    only order Pinales — so both are checked and broadleaf is the fallback.
    """
    if plant_class != "tree":
        return f"sil_{plant_class}"
    if (gbif_class or "").strip().lower() == "pinopsida":
        return "sil_tree_conifer"
    if (gbif_order or "").strip().lower() == "pinales":
        return "sil_tree_conifer"
    return "sil_tree_broadleaf"


# --------------------------------------------------------------------------
# Wikipedia / Wikimedia Commons
# --------------------------------------------------------------------------

WIKI_API = "https://en.wikipedia.org/w/api.php"
WIKI_REST = "https://en.wikipedia.org/api/rest_v1/page/summary/"
COMMONS_API = "https://commons.wikimedia.org/w/api.php"


def wiki_summary(title: str, refresh: bool):
    url = WIKI_REST + urllib.parse.quote(title.replace(" ", "_"), safe="")
    payload, _ = fetch_json(url, refresh=refresh, delay=DELAY_WIKI)
    if not payload or payload.get("type") == "https://mediawiki.org/wiki/HyperSwitch/errors/not_found":
        return None
    if payload.get("title", "").lower() in ("not found.",):
        return None
    return payload


def wiki_sections(title: str, refresh: bool):
    url = WIKI_API + "?" + urllib.parse.urlencode(
        {"action": "parse", "page": title, "prop": "sections", "format": "json", "formatversion": "2"}
    )
    payload, _ = fetch_json(url, refresh=refresh, delay=DELAY_WIKI)
    if not payload or "parse" not in payload:
        return []
    return payload["parse"].get("sections", [])


def wiki_section_wikitext(title: str, index: str, refresh: bool):
    url = WIKI_API + "?" + urllib.parse.urlencode(
        {
            "action": "parse",
            "page": title,
            "section": index,
            "prop": "wikitext",
            "format": "json",
            "formatversion": "2",
        }
    )
    payload, _ = fetch_json(url, refresh=refresh, delay=DELAY_WIKI)
    if not payload or "parse" not in payload:
        return None
    return payload["parse"].get("wikitext")


def pick_habitat_section(sections):
    """Prefer a section titled habitat, then distribution, then range, then ecology."""
    # "Ecology" is last: on some articles (Sea Otter) it is about metabolism,
    # while "Distribution" and "Range" are reliably about where the animal lives.
    for want in ("habitat", "distribution", "range", "ecology"):
        for sec in sections:
            line = (sec.get("line") or "").strip().lower()
            if want in line:
                return sec
    return None


TEMPLATE_RE = re.compile(r"\{\{[^{}]*\}\}")
REF_RE = re.compile(r"<ref[^>]*?/>|<ref[^>]*?>.*?</ref>", re.S | re.I)
COMMENT_RE = re.compile(r"<!--.*?-->", re.S)
TAG_RE = re.compile(r"<[^>]+>")
TABLE_RE = re.compile(r"\{\|.*?\|\}", re.S)
FILE_LINK_START_RE = re.compile(r"\[\[(?:File|Image|Category)\s*:", re.I)
CONVERT_RE = re.compile(r"\{\{\s*(?:convert|cvt)\s*\|([^{}]*)\}\}", re.I)
FRAC_RE = re.compile(r"\{\{\s*(?:frac|fraction|sfrac)\s*\|([^{}]*)\}\}", re.I)


def expand_convert(text: str) -> str:
    """Turn `{{convert|10|m|ft}}` into `10 m` before templates are stripped.

    Measurements are common in habitat prose ("to a depth of between 10 and 50
    m"), and dropping the template wholesale leaves sentences like
    "to a depth of between ." behind.
    """

    def repl(m):
        args = [a.strip() for a in m.group(1).split("|") if a.strip() and "=" not in a]
        if not args:
            return ""
        numeric = re.compile(r"^[\d.,]+$")
        if len(args) >= 4 and not numeric.match(args[1]) and numeric.match(args[2]):
            # Range form: {{convert|10|to|50|m|ft}}, {{convert|15|to(-)|23|m}}, …
            # The separator argument has many spellings; normalise it to "to".
            return f"{args[0]} to {args[2]} {args[3]}"
        if len(args) >= 2:
            return f"{args[0]} {args[1]}"
        return args[0]

    for _ in range(3):
        new = CONVERT_RE.sub(repl, text)
        if new == text:
            break
        text = new

    def frac(m):
        args = [a.strip() for a in m.group(1).split("|") if a.strip()]
        if len(args) == 1:
            return f"1/{args[0]}"
        if len(args) == 2:
            return f"{args[0]}/{args[1]}"
        return f"{args[0]} {args[1]}/{args[2]}"

    return FRAC_RE.sub(frac, text)


def strip_file_links(text: str) -> str:
    """Remove [[File:...]] / [[Image:...]] / [[Category:...]] including nested links.

    Captions routinely contain their own [[wiki links]], so a non-greedy regex
    stops at the wrong `]]` and leaves caption debris in the prose.
    """
    out = []
    i = 0
    while i < len(text):
        m = FILE_LINK_START_RE.match(text, i)
        if not m:
            out.append(text[i])
            i += 1
            continue
        depth = 0
        j = i
        while j < len(text):
            if text.startswith("[[", j):
                depth += 1
                j += 2
            elif text.startswith("]]", j):
                depth -= 1
                j += 2
                if depth == 0:
                    break
            else:
                j += 1
        i = j
    return "".join(out)


def strip_wikitext(text: str) -> str:
    if not text:
        return ""
    text = COMMENT_RE.sub("", text)
    text = REF_RE.sub("", text)
    text = expand_convert(text)
    text = TABLE_RE.sub("", text)
    text = strip_file_links(text)
    # Templates can nest; peel a few layers.
    for _ in range(5):
        new = TEMPLATE_RE.sub("", text)
        if new == text:
            break
        text = new
    # [[target|label]] -> label ; [[target]] -> target
    text = re.sub(r"\[\[([^\]|]*)\|([^\]]*)\]\]", r"\2", text)
    text = re.sub(r"\[\[([^\]]*)\]\]", r"\1", text)
    # [http://x label] -> label
    text = re.sub(r"\[https?://\S+\s+([^\]]*)\]", r"\1", text)
    text = re.sub(r"\[https?://\S+\]", "", text)
    text = TAG_RE.sub("", text)
    text = text.replace("'''", "").replace("''", "")
    # Drop headings, list markers and leftover pipes.
    lines = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("=") or line.startswith("|") or line.startswith("!"):
            continue
        line = re.sub(r"^[*#:;]+\s*", "", line)
        lines.append(line)
    text = " ".join(lines)
    text = re.sub(r"&nbsp;", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


SENTENCE_RE = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9\"'(])")


def first_sentences(text: str, count: int, max_chars: int = 600) -> str:
    if not text:
        return ""
    parts = SENTENCE_RE.split(text)
    out = " ".join(parts[:count]).strip()
    if len(out) > max_chars:
        # Trim back to a sentence boundary if we can.
        cut = out[:max_chars].rsplit(". ", 1)[0]
        out = (cut + ".") if cut else out[:max_chars].rstrip() + "…"
    return out


def clean_image_url(url: str | None) -> str | None:
    """Drop the REST API's utm_* tracking query string from an upload URL."""
    if not url:
        return None
    return url.split("?", 1)[0]


def commons_filename(image_url: str) -> str:
    """Recover the Commons File: name from an upload.wikimedia.org URL.

    Plain URLs end in the file name.  Thumbnail URLs look like
    `.../commons/thumb/4/40/<name>.jpg/3840px-<name>.jpg`, where the real file
    name is the segment *before* the last one.
    """
    path = urllib.parse.urlsplit(image_url).path
    parts = [p for p in path.split("/") if p]
    name = parts[-1]
    if "/thumb/" in path and len(parts) >= 2:
        name = parts[-2]
    return urllib.parse.unquote(name)


def commons_attribution(image_url: str, refresh: bool):
    """Build `Wikimedia Commons · <license> · <author>` from extmetadata."""
    if not image_url:
        return None
    filename = commons_filename(image_url)
    url = COMMONS_API + "?" + urllib.parse.urlencode(
        {
            "action": "query",
            "titles": "File:" + filename,
            "prop": "imageinfo",
            "iiprop": "extmetadata",
            "format": "json",
            "formatversion": "2",
        }
    )
    payload, _ = fetch_json(url, refresh=refresh, delay=DELAY_WIKI)
    if not payload:
        return None
    pages = payload.get("query", {}).get("pages", [])
    if not pages or "imageinfo" not in pages[0]:
        return None
    meta = pages[0]["imageinfo"][0].get("extmetadata", {})

    def val(key):
        raw = meta.get(key, {}).get("value")
        if not raw:
            return None
        return strip_html(str(raw))

    license_name = val("LicenseShortName") or val("License") or "see Commons"
    author = val("Artist") or val("Credit") or "unknown"
    author = re.sub(r"\s+", " ", author).strip(" ·,;")
    if len(author) > 80:
        author = author[:77].rstrip() + "…"
    return f"Wikimedia Commons · {license_name} · {author}"


def strip_html(value: str) -> str:
    value = re.sub(r"<[^>]+>", " ", value)
    value = re.sub(r"&amp;", "&", value)
    value = re.sub(r"&#\d+;", "", value)
    value = unicodedata.normalize("NFC", value)
    return re.sub(r"\s+", " ", value).strip()


# --------------------------------------------------------------------------
# Xeno-canto v3
# --------------------------------------------------------------------------

def xeno_canto_best(scientific_name: str, api_key: str, refresh: bool):
    query = urllib.parse.quote(f'sp:"{scientific_name}"')
    url = f"https://xeno-canto.org/api/3/recordings?query={query}&key={urllib.parse.quote(api_key)}"
    payload, _ = fetch_json(url, refresh=refresh, delay=DELAY_XC)
    if not payload:
        return None
    recordings = payload.get("recordings") or []
    if not recordings:
        return None
    ranked = sorted(recordings, key=lambda r: {"A": 0, "B": 1, "C": 2, "D": 3, "E": 4}.get(r.get("q", "E"), 5))
    return ranked[0]


# --------------------------------------------------------------------------
# Dr. Duke's ethnobotanical database (CC0)
# --------------------------------------------------------------------------

def duke_binomial(name: str) -> str:
    """Normalise a name to the `genus species` key the Duke's index uses.

    Lower-cased and whitespace-collapsed, per R15; anything stranger than that
    is handled by the curator's `dukeName` pin rather than by more heuristics.
    """
    parts = re.sub(r"\s+", " ", (name or "").strip()).split(" ")
    if len(parts) < 2:
        return ""
    return f"{parts[0].lower()} {parts[1].lower()}"


def download_duke(refresh: bool) -> bytes:
    """Fetch Duke-Source-CSV.zip, resolving the URL through the figshare API."""
    zip_path = DUKE_CACHE_DIR / DUKE_ZIP_NAME
    if zip_path.exists() and not refresh:
        return fetch_bytes("", zip_path, refresh=False)
    listing = fetch_json(DUKE_ARTICLE_API, refresh=refresh, delay=DELAY_GBIF)[0] or []
    download_url = None
    for item in listing:
        if item.get("name") == DUKE_ZIP_NAME:
            download_url = item.get("download_url")
            break
    if not download_url:
        raise SystemExit(
            f"figshare article listing did not name {DUKE_ZIP_NAME}; "
            f"check {DUKE_ARTICLE_API} by hand"
        )
    return fetch_bytes(download_url, zip_path, refresh=refresh)


def build_duke_index(refresh: bool):
    """`{"genus species": {"a": [activities], "n": count, "p": poison}}`.

    `n` and `a` both EXCLUDE `Poison` records — `n` is `medicinalRecordCount`
    and `a` is `medicinalActivities`, so the bundled asset and the pipeline's
    own numbers are the same numbers. `p` is the poison flag the caution rule
    and the confirm card read.
    """
    data = download_duke(refresh)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        raw = archive.read(DUKE_ETHNOBOT_CSV)
    text = raw.decode("utf-8-sig", errors="replace")
    rows = csv.DictReader(io.StringIO(text))

    activities = defaultdict(list)
    poison = set()
    total_rows = 0
    for row in rows:
        total_rows += 1
        key = duke_binomial(f"{row.get('GENUS', '')} {row.get('SPECIES', '')}")
        if not key:
            continue
        activity = re.sub(r"\s+", " ", (row.get("ACTIVITY") or "").strip())
        if not activity:
            continue
        if activity.lower() == "poison":
            poison.add(key)
            continue
        activities[key].append(activity)

    index = {}
    for key in set(activities) | poison:
        counts = Counter(activities.get(key, []))
        index[key] = {
            "a": [name.title() for name, _ in counts.most_common(MEDICINAL_ACTIVITY_CAP)],
            "n": sum(counts.values()),
            "p": key in poison,
            # Not written to the asset — the medicinal rule counts distinct
            # activities, and the report shows both numbers.
            "_distinct": len(counts),
        }
    return index, total_rows


def duke_lookup(index, accepted: str, synonyms, pinned):
    """First hit wins: accepted name, then each GBIF synonym, then the pin.

    Returns `(record, provenance)` where provenance is one of `duke:accepted`,
    `duke:synonym:<Name>`, `duke:pinned:<Name>` or `duke:none`.
    """
    key = duke_binomial(accepted)
    if key in index:
        return index[key], "duke:accepted"
    for synonym in synonyms:
        key = duke_binomial(synonym)
        if key in index:
            return index[key], f"duke:synonym:{synonym}"
    if pinned:
        key = duke_binomial(pinned)
        if key in index:
            return index[key], f"duke:pinned:{pinned}"
    return None, "duke:none"


def write_duke_asset(index, path: Path):
    """The bundled lookup table the app's DukeIndex reads (11.3).

    Shape, frozen here because slice 12 parses it:

        {"format": "biodex-duke-1", "source": ..., "license": "CC0",
         "taxa": {"genus species": {"a": ["Astringent", ...], "n": 105, "p": true}}}

    Activity names are stored inline rather than through a string table: the
    whole file lands near 1.1 MB either way, and an inline map is what a
    twelve-taxon test fixture can be cut out of by hand.
    """
    taxa = {}
    for key, record in sorted(index.items()):
        taxa[key] = {"a": record["a"], "n": record["n"], "p": record["p"]}
    document = {
        "format": "biodex-duke-1",
        "source": "Dr. Duke's Phytochemical and Ethnobotanical Databases, USDA ARS",
        "license": "CC0",
        "attribution": DUKE_ATTRIBUTION,
        "note": "n and a exclude Poison records; p is true when any Poison record exists.",
        "taxa": taxa,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        json.dump(document, fh, ensure_ascii=False, separators=(",", ":"))
        fh.write("\n")
    return path.stat().st_size


# --------------------------------------------------------------------------
# Assembly
# --------------------------------------------------------------------------

def slugify(name: str) -> str:
    text = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode("ascii")
    text = text.lower().replace("'", "").replace("’", "")
    text = re.sub(r"[^a-z0-9]+", "-", text)
    return text.strip("-")


USES_SECTION_WORDS = ("uses", "culinary", "edib", "medicin", "ethnobot")


def collect_uses_review(title, sections, refresh, common, uses_review):
    """Append any uses-ish section's prose to the curator's review file.

    This is a *check* on the hand-written `usesNote`, never a source for it —
    the asset's `provenance.uses` is always "curated" for the edible half
    (11.3 step 3). Nothing here is copied into the catalogue.
    """
    for section in sections:
        line = (section.get("line") or "").strip()
        if not any(word in line.lower() for word in USES_SECTION_WORDS):
            continue
        wikitext = wiki_section_wikitext(title, str(section.get("index")), refresh)
        prose = strip_wikitext(wikitext or "")
        if not prose:
            continue
        uses_review.append(f"### {common} — {title} § {line}\n{prose[:600]}\n")


def fetch_wikipedia(entry, accepted, common, refresh, report, prov, warnings, uses_review=None):
    """The Wikipedia/Commons half of a record, shared by both kingdoms.

    `uses_review` is the plant path's curator aid: when a list is passed, any
    section whose title looks like a uses section is stripped and appended for
    review. Nothing it collects ever reaches the asset (11.3 step 3).
    """
    habitat_text = None
    description = None
    image_url = None
    info_url = None
    image_attr = None
    habitat_source = None

    wiki_title = None
    pinned = entry.get("wikipediaTitle")
    if pinned:
        # The curator pinned the article — GBIF's accepted name resolves to the
        # wrong page (a lump, a synonym redirect) often enough to need this.
        summary = wiki_summary(pinned, refresh)
        if summary:
            wiki_title = summary.get("title")
            prov["wikipediaTitle"] = f"wikipedia:pinned:{wiki_title}"
        else:
            warnings.append(f"pinned wikipediaTitle '{pinned}' did not resolve")
    else:
        summary = None

    if not summary:
        summary = wiki_summary(accepted, refresh)
        if summary:
            wiki_title = summary.get("title")
            prov["wikipediaTitle"] = f"wikipedia:scientific:{wiki_title}"
        else:
            summary = wiki_summary(common, refresh)
            if summary:
                wiki_title = summary.get("title")
                prov["wikipediaTitle"] = f"wikipedia:common:{wiki_title}"

    if summary:
        description = first_sentences(strip_html(summary.get("extract") or ""), 2)
        if description:
            prov["description"] = "wikipedia:summary"
        image_url = clean_image_url((summary.get("originalimage") or {}).get("source"))
        if image_url:
            prov["imageUrl"] = "wikipedia:summary:originalimage"
        info_url = (summary.get("content_urls") or {}).get("desktop", {}).get("page")
        if info_url:
            prov["infoUrl"] = "wikipedia:summary"

        sections = wiki_sections(wiki_title, refresh)
        section = pick_habitat_section(sections)
        if section:
            wikitext = wiki_section_wikitext(wiki_title, str(section.get("index")), refresh)
            prose = strip_wikitext(wikitext or "")
            candidate = first_sentences(prose, 3)
            if len(candidate) >= 60:
                habitat_text = candidate
                habitat_source = f"wikipedia:section:{section.get('line')}"
        if not habitat_text and description:
            habitat_text = first_sentences(strip_html(summary.get("extract") or ""), 3)
            habitat_source = "wikipedia:lede"
            report["lede_fallback"].append(common)
    else:
        warnings.append("no Wikipedia page found by scientific or common name")
        report["no_wikipedia"].append(common)

    if habitat_source:
        prov["habitatText"] = habitat_source

    if image_url:
        image_attr = commons_attribution(image_url, refresh)
        if image_attr:
            prov["imageAttribution"] = "wikimedia:imageinfo:extmetadata"

    if uses_review is not None and summary and wiki_title:
        collect_uses_review(wiki_title, sections, refresh, common, uses_review)

    return {
        "habitatText": habitat_text,
        "description": description,
        "imageUrl": image_url,
        "infoUrl": info_url,
        "imageAttribution": image_attr,
    }


def build_species(entry, ecosystem_ids, api_key, refresh, report):
    common = entry["commonName"]
    curated_sci = entry["scientificName"]
    slug = slugify(common)
    prov = {}
    warnings = []

    # --- GBIF -------------------------------------------------------------
    match = gbif_match(curated_sci, refresh) or {}
    accepted = match.get("species") or match.get("canonicalName") or curated_sci
    match_type = match.get("matchType", "NONE")
    confidence = match.get("confidence", 0)
    rank = match.get("rank")
    gbif_class = match.get("class")

    if match_type != "EXACT" or confidence < 95 or match.get("status") not in (None, "ACCEPTED"):
        warnings.append(
            f"GBIF {match_type}/{confidence}%/{match.get('status')} "
            f"curated='{curated_sci}' accepted='{accepted}'"
        )
    if rank and rank != "SPECIES":
        warnings.append(f"GBIF rank is {rank}, not SPECIES")

    gbif_kingdom = match.get("kingdom")
    if (gbif_kingdom or "").strip().lower() != "animalia":
        # 11.3: a kingdom contradiction is a curator typo that must not ship.
        raise SystemExit(
            f"{common}: declared an animal but GBIF matched '{accepted}' in "
            f"kingdom {gbif_kingdom or 'NONE'}"
        )
    prov["kingdom"] = f"gbif:kingdom:{gbif_kingdom}"

    gbif_phylum = match.get("phylum")
    tax_class = tax_class_for(gbif_class, gbif_phylum)
    prov["scientificName"] = "gbif"
    if gbif_class:
        prov["taxClass"] = f"gbif:class:{gbif_class}"
    elif gbif_phylum:
        prov["taxClass"] = f"gbif:phylum:{gbif_phylum}:no-class"
    else:
        prov["taxClass"] = "gbif:unmatched"
    if not gbif_class and not gbif_phylum:
        warnings.append("GBIF returned neither class nor phylum; taxClass is a guess")

    # --- Wikipedia --------------------------------------------------------
    wiki = fetch_wikipedia(entry, accepted, common, refresh, report, prov, warnings)
    habitat_text = wiki["habitatText"]
    description = wiki["description"]
    image_url = wiki["imageUrl"]
    info_url = wiki["infoUrl"]
    image_attr = wiki["imageAttribution"]

    # --- Xeno-canto -------------------------------------------------------
    call_url = None
    call_attr = None
    if api_key:
        rec = xeno_canto_best(accepted, api_key, refresh)
        if rec:
            call_url = rec.get("file") or rec.get("url")
            xc_id = rec.get("id")
            call_attr = (
                f"Xeno-canto XC{xc_id} · {rec.get('lic') or 'see Xeno-canto'} · {rec.get('rec') or 'unknown'}"
            )
            prov["callUrl"] = "xeno-canto:v3"
            prov["callAttribution"] = "xeno-canto:v3"
        else:
            report["no_call_none_found"].append(common)
    else:
        report["no_call_key_absent"].append(common)

    record = {
        "id": slug,
        "dexNumber": entry["dexNumber"],
        "commonName": common,
        "scientificName": accepted,
        "taxClass": tax_class,
        "ecosystemIds": entry["ecosystemIds"],
        "habitatText": habitat_text,
        "description": description,
        "imageUrl": image_url,
        "callUrl": call_url,
        "infoUrl": info_url,
        "imageAttribution": image_attr,
        "callAttribution": call_attr,
        "silhouetteRes": f"sil_{tax_class}",
        # The uses block is plant-only (DESIGN.md D14): whether an animal is
        # edible is a hunting-and-fishing regulation question, not a field-guide
        # fact, and the app stays out of it.
        "kingdom": "animal",
        "uses": [],
        "usesNote": None,
        "medicinalActivities": [],
        "medicinalRecordCount": 0,
        "usesAttribution": None,
        "provenance": prov,
    }

    # --- curator overrides, applied last ----------------------------------
    for field, value in (entry.get("overrides") or {}).items():
        record[field] = value
        prov[field] = "override"

    for warn in warnings:
        report["gbif_warnings"].append(f"#{entry['dexNumber']:3d} {common}: {warn}")

    unknown_eco = [e for e in entry["ecosystemIds"] if e not in ecosystem_ids]
    if unknown_eco:
        raise SystemExit(f"{common}: undeclared ecosystemIds {unknown_eco}")

    return record



CAUTION_RE = re.compile(r"(?:^|(?<=[.!?])\s)Caution:", re.I)


def has_caution(note) -> bool:
    """True when the note carries a sentence beginning `Caution:`.

    Matched at a sentence start so the same rule the app's `UsesNote.cautionSplit`
    uses to render the emphasis is the rule the build enforces (11.2).
    """
    return bool(note) and bool(CAUTION_RE.search(note))


def build_plant(entry, ecosystem_ids, duke_index, refresh, report, uses_review):
    common = entry["commonName"]
    curated_sci = entry["scientificName"]
    plant_class = entry.get("plantClass")
    slug = slugify(common)
    prov = {}
    warnings = []

    if plant_class not in PLANT_CLASSES:
        raise SystemExit(f"{common}: plantClass must be one of {PLANT_CLASSES}, got {plant_class!r}")

    # --- GBIF -------------------------------------------------------------
    match = gbif_match(curated_sci, refresh) or {}
    accepted = match.get("species") or match.get("canonicalName") or curated_sci
    match_type = match.get("matchType", "NONE")
    confidence = match.get("confidence", 0)
    rank = match.get("rank")
    gbif_class = match.get("class")
    gbif_order = match.get("order")
    gbif_kingdom = match.get("kingdom")
    usage_key = match.get("usageKey") or match.get("speciesKey")

    if (gbif_kingdom or "").strip().lower() != "plantae":
        raise SystemExit(
            f"{common}: declared a plant but GBIF matched '{accepted}' in "
            f"kingdom {gbif_kingdom or 'NONE'}"
        )
    prov["kingdom"] = f"gbif:kingdom:{gbif_kingdom}"
    prov["scientificName"] = "gbif"
    prov["taxClass"] = "curated"  # growth form is editorial judgment (D13)

    if match_type != "EXACT" or confidence < 95 or match.get("status") not in (None, "ACCEPTED"):
        warnings.append(
            f"GBIF {match_type}/{confidence}%/{match.get('status')} "
            f"curated='{curated_sci}' accepted='{accepted}'"
        )
    if rank and rank != "SPECIES":
        warnings.append(f"GBIF rank is {rank}, not SPECIES")
    if plant_class == "fern" and (gbif_class or "").strip().lower() != "polypodiopsida":
        warnings.append(f"plantClass is fern but GBIF class is {gbif_class or 'NONE'}")

    silhouette = plant_silhouette(plant_class, gbif_class, gbif_order)
    prov["silhouetteRes"] = f"gbif:class:{gbif_class}/order:{gbif_order}"

    # --- Duke's -----------------------------------------------------------
    synonyms = gbif_synonyms(usage_key, refresh)
    record, duke_prov = duke_lookup(duke_index, accepted, synonyms, entry.get("dukeName"))
    prov["duke"] = duke_prov

    activities = record["a"] if record else []
    record_count = record["n"] if record else 0
    distinct = record["_distinct"] if record else 0
    poison = bool(record and record["p"])

    medicinal = distinct >= MEDICINAL_ACTIVITY_THRESHOLD
    override_medicinal = (entry.get("overrides") or {}).get("medicinal")
    if override_medicinal is not None:
        medicinal = bool(override_medicinal)
        medicinal_prov = "override"
    else:
        medicinal_prov = duke_prov

    if not record:
        report["duke_no_record"].append(f"{common} ({accepted})")
    elif duke_prov.startswith("duke:synonym") or duke_prov.startswith("duke:pinned"):
        report["duke_indirect"].append(f"{common} ({accepted}) matched via {duke_prov}")
    if poison:
        report["duke_poison"].append(f"{common} ({accepted})")

    edible = bool(entry.get("edible"))
    uses = (["edible"] if edible else []) + (["medicinal"] if medicinal else [])
    # The two tags have different provenance and the asset says which is which
    # (D14): edible is the curator's, medicinal is Duke's or an explicit pin.
    prov["uses"] = {"edible": "curated", "medicinal": medicinal_prov}

    uses_note = entry.get("usesNote")

    # --- Wikipedia (and the curator's uses-review aid) ----------------------
    wiki = fetch_wikipedia(entry, accepted, common, refresh, report, prov, warnings, uses_review)

    record_out = {
        "id": slug,
        "dexNumber": entry["dexNumber"],
        "commonName": common,
        "scientificName": accepted,
        "taxClass": plant_class,
        "kingdom": "plant",
        "ecosystemIds": entry["ecosystemIds"],
        "habitatText": wiki["habitatText"],
        "description": wiki["description"],
        "imageUrl": wiki["imageUrl"],
        # 11.3 step 4: Xeno-canto is skipped for plants without a request.
        "callUrl": None,
        "infoUrl": wiki["infoUrl"],
        "imageAttribution": wiki["imageAttribution"],
        "callAttribution": None,
        "silhouetteRes": silhouette,
        "uses": uses,
        "usesNote": uses_note if uses else None,
        # These three come straight from the Duke's hit, tag or no tag: they are
        # what the source recorded, and the `medicinal` tag is a rule applied on
        # top of them rather than a filter over them.
        "medicinalActivities": activities,
        "medicinalRecordCount": record_count,
        "usesAttribution": DUKE_ATTRIBUTION if activities else None,
        "provenance": prov,
        # Pipeline-internal, stripped before the asset is written.
        "_poison": poison,
        "_dukeMatchedName": (
            accepted if duke_prov == "duke:accepted"
            else duke_prov.split(":", 2)[2] if record else None
        ),
        "_dukeDistinct": distinct,
        "_dukeRecordCount": record_count,
        "_curatedNote": uses_note,
    }

    for field, value in (entry.get("overrides") or {}).items():
        if field == "medicinal":
            continue  # already folded into the uses tag above
        record_out[field] = value
        prov[field] = "override"

    for warn in warnings:
        report["gbif_warnings"].append(f"P{entry['dexNumber']:03d} {common}: {warn}")

    unknown_eco = [e for e in entry["ecosystemIds"] if e not in ecosystem_ids]
    if unknown_eco:
        raise SystemExit(f"{common}: undeclared ecosystemIds {unknown_eco}")

    return record_out


ANIMAL_CLASSES = {"bird", "mammal", "reptile", "amphibian", "fish", "insect", "other_invertebrate"}
PLANT_SILHOUETTES = {
    "shrub": {"sil_shrub"},
    "herb": {"sil_herb"},
    "fern": {"sil_fern"},
    "tree": {"sil_tree_conifer", "sil_tree_broadleaf"},
}


def validate(catalogue, ecosystem_ids, internals):
    """Everything section 11.3 says must hold before the asset is written.

    `internals` maps a species id to the pipeline-only facts the asset does not
    carry — chiefly whether Duke's records the species as a poison, which is
    what makes the caution set a decision of the source rather than of whoever
    wrote the notes.
    """
    species = catalogue["species"]
    problems = []

    animals = [s for s in species if s["kingdom"] == "animal"]
    plants = [s for s in species if s["kingdom"] == "plant"]
    if len(species) != 200:
        problems.append(f"expected 200 species, got {len(species)}")
    if len(animals) != 120:
        problems.append(f"expected 120 animals, got {len(animals)}")
    if len(plants) != 80:
        problems.append(f"expected 80 plants, got {len(plants)}")
    if sorted(s["dexNumber"] for s in animals) != list(range(1, 121)):
        problems.append("animal dexNumbers are not exactly 1..120 with no duplicates")
    if sorted(s["dexNumber"] for s in plants) != list(range(1, 81)):
        problems.append("plant dexNumbers are not exactly 1..80 with no duplicates")

    dupes = [i for i, n in Counter(s["id"] for s in species).items() if n > 1]
    if dupes:
        problems.append(f"duplicate species ids: {dupes}")

    for s in species:
        sid = s["id"]
        for field in ("id", "commonName", "scientificName", "taxClass", "silhouetteRes", "kingdom"):
            if not s.get(field):
                problems.append(f"{sid}: missing {field}")
        if not s["ecosystemIds"]:
            problems.append(f"{sid}: no ecosystemIds")
        for eco in s["ecosystemIds"]:
            if eco not in ecosystem_ids:
                problems.append(f"{sid}: undeclared ecosystem {eco}")
        if set(s["uses"]) - {"edible", "medicinal"}:
            problems.append(f"{sid}: uses outside {{edible, medicinal}}: {s['uses']}")
        if not s["uses"] and s["usesNote"]:
            problems.append(f"{sid}: usesNote present but no use tag")
        if not s["medicinalActivities"] and s["usesAttribution"]:
            problems.append(f"{sid}: usesAttribution set with no Duke's activities")

        if s["kingdom"] == "animal":
            if s["taxClass"] not in ANIMAL_CLASSES:
                problems.append(f"{sid}: bad animal taxClass {s['taxClass']}")
            elif s["silhouetteRes"] != f"sil_{s['taxClass']}":
                problems.append(f"{sid}: silhouetteRes does not match taxClass")
            if s["uses"] or s["usesNote"] or s["medicinalActivities"] \
                    or s["medicinalRecordCount"] or s["usesAttribution"]:
                problems.append(f"{sid}: an animal carries a uses/Duke's field")
        elif s["kingdom"] == "plant":
            if s["taxClass"] not in PLANT_CLASSES:
                problems.append(f"{sid}: bad plant taxClass {s['taxClass']}")
            elif s["silhouetteRes"] not in PLANT_SILHOUETTES[s["taxClass"]]:
                problems.append(
                    f"{sid}: silhouetteRes {s['silhouetteRes']} does not match "
                    f"plant class {s['taxClass']}"
                )
            internal = internals.get(sid, {})
            edible = "edible" in s["uses"]
            medicinal = "medicinal" in s["uses"]
            if edible and not s["usesNote"]:
                problems.append(f"{sid}: edible but no usesNote naming the part and the season")
            if s["usesNote"] and len(s["usesNote"]) > USES_NOTE_MAX_CHARS:
                problems.append(
                    f"{sid}: usesNote is {len(s['usesNote'])} characters, over {USES_NOTE_MAX_CHARS}"
                )
            if s["provenance"].get("uses", {}).get("medicinal") != "override":
                if medicinal != (len(s["medicinalActivities"]) >= MEDICINAL_ACTIVITY_THRESHOLD):
                    problems.append(
                        f"{sid}: medicinal={medicinal} disagrees with "
                        f"{len(s['medicinalActivities'])} Duke's activities"
                    )
            # The poison rule (11.3). A Duke's `Poison` record makes a `Caution:`
            # sentence mandatory, so the cautioned set is decided by the source.
            if internal.get("poison") and s["uses"] and not has_caution(s["usesNote"]):
                problems.append(
                    f"{sid}: Duke's records a Poison for this species and it carries a "
                    f"use tag, but its usesNote has no 'Caution:' sentence"
                )
        else:
            problems.append(f"{sid}: unknown kingdom {s['kingdom']}")

    return problems


def validate_duke_asset(path, species, internals):
    """The bundled Duke's table parses and holds every plant that had a hit."""
    problems = []
    try:
        with path.open(encoding="utf-8") as fh:
            document = json.load(fh)
    except Exception as exc:
        return [f"{path.name}: does not parse ({type(exc).__name__}: {exc})"]
    taxa = document.get("taxa") or {}
    if not taxa:
        problems.append(f"{path.name}: no taxa")
    for s in species:
        if s["kingdom"] != "plant":
            continue
        internal = internals.get(s["id"], {})
        matched = internal.get("dukeMatchedName")
        if matched and duke_binomial(matched) not in taxa:
            problems.append(f"{path.name}: missing {matched}, which {s['id']} joined on")
    return problems


def write_report(catalogue, report, path, had_key, internals, duke_rows, duke_bytes):
    species = catalogue["species"]
    animals = [s for s in species if s["kingdom"] == "animal"]
    plants = [s for s in species if s["kingdom"] == "plant"]
    lines = []
    add = lines.append
    add("BioDex — catalogue build report")
    add(f"generated: {time.strftime('%Y-%m-%d %H:%M:%S')}")
    add(f"HTTP requests made: {HTTP_REQUESTS}   cache hits: {CACHE_HITS}")
    add("")

    def coverage(title, group, *, calls):
        total = len(group)
        if not total:
            return
        add(title)
        add(f"  species              {total}")
        add(f"  habitatText          {sum(1 for s in group if s['habitatText'])}/{total}")
        add(f"  description          {sum(1 for s in group if s['description'])}/{total}")
        add(f"  imageUrl             {sum(1 for s in group if s['imageUrl'])}/{total}")
        add(f"  imageAttribution     {sum(1 for s in group if s['imageAttribution'])}/{total}")
        add(f"  infoUrl              {sum(1 for s in group if s['infoUrl'])}/{total}")
        if calls:
            add(f"  callUrl              {sum(1 for s in group if s['callUrl'])}/{total}")
        else:
            add("  callUrl              n/a — Xeno-canto is skipped for plants")
        add("")

    add(f"COVERAGE  (lede fallback across both kingdoms: {len(report['lede_fallback'])})")
    add("")
    coverage("ANIMALS", animals, calls=True)
    coverage("PLANTS", plants, calls=False)

    add("CALLS (animals only)")
    if had_key:
        add(f"  missing because no Xeno-canto recording exists: {len(report['no_call_none_found'])}")
        add("  missing because XC_API_KEY was absent:           0")
    else:
        add("  XC_API_KEY was NOT set — Xeno-canto was skipped entirely.")
        add(f"  missing because XC_API_KEY was absent:           {len(report['no_call_key_absent'])}")
        add("  missing because no Xeno-canto recording exists:  0 (not checked)")
    add("")

    add("CLASS DISTRIBUTION")
    for kingdom, group in (("animal", animals), ("plant", plants)):
        add(f"  {kingdom}")
        for cls, n in sorted(Counter(s["taxClass"] for s in group).items(), key=lambda kv: -kv[1]):
            add(f"    {cls:20s} {n}")
    add("")

    add("USES (plants only)")
    edible = [s for s in plants if "edible" in s["uses"]]
    medicinal = [s for s in plants if "medicinal" in s["uses"]]
    both = [s for s in plants if len(s["uses"]) == 2]
    add(f"  edible                {len(edible)}/{len(plants)}   (curated)")
    add(f"  medicinal             {len(medicinal)}/{len(plants)}   "
        f"(derived: >= {MEDICINAL_ACTIVITY_THRESHOLD} distinct non-Poison Duke's activities)")
    add(f"  both                  {len(both)}")
    add(f"  no use tag            {sum(1 for s in plants if not s['uses'])}")
    add(f"  carrying a Caution:   {sum(1 for s in plants if has_caution(s['usesNote']))}")
    add("")
    add(f"DUKE'S SOURCE  ETHNOBOT.csv rows parsed: {duke_rows}; "
        f"duke_ethnobot.json: {duke_bytes / 1e6:.2f} MB")
    add("")

    add("ECOSYSTEM DISTRIBUTION (a species counts in each of its ecosystems)")
    animal_eco, plant_eco = Counter(), Counter()
    for s in animals:
        animal_eco.update(s["ecosystemIds"])
    for s in plants:
        plant_eco.update(s["ecosystemIds"])
    for eco in catalogue["ecosystems"]:
        add(f"  {eco['name']:30s} {animal_eco[eco['id']]:3d} animals  {plant_eco[eco['id']]:3d} plants")
    add("")

    def block(title, items):
        add(f"{title} ({len(items)})")
        for item in items:
            add(f"  - {item}")
        add("")

    block("GBIF MATCHES NEEDING CURATOR REVIEW", report["gbif_warnings"])
    block(
        "DUKE'S — DERIVED MEDICINAL SET",
        [
            f"{s['commonName']} ({s['scientificName']}): {internals[s['id']]['distinct']} activities, "
            f"{s['medicinalRecordCount']} records — {', '.join(s['medicinalActivities'][:4])}"
            for s in medicinal
        ],
    )
    block("DUKE'S — MATCHED THROUGH A SYNONYM OR A PIN", report["duke_indirect"])
    block("DUKE'S — NO RECORD (an ordinary outcome, not a failure)", report["duke_no_record"])
    block("DUKE'S — POISON RECORDED (each must carry a Caution: sentence)", report["duke_poison"])
    block(
        "DUKE'S POISON BUT NO USE TAG — the caution has nowhere to render",
        [
            f"{s['commonName']} ({s['scientificName']})"
            for s in plants
            if internals[s["id"]]["poison"] and not s["uses"]
        ],
    )
    block(
        "PLANTS BELOW THE MEDICINAL THRESHOLD BUT WITH A DUKE'S RECORD",
        [
            f"{s['commonName']}: {internals[s['id']]['distinct']} activities"
            for s in plants
            if "medicinal" not in s["uses"] and internals[s["id"]]["recordCount"]
        ],
    )
    block("HABITAT TEXT FELL BACK TO THE SUMMARY LEDE", report["lede_fallback"])
    block("NO WIKIPEDIA PAGE FOUND", report["no_wikipedia"])
    block("NO IMAGE URL", [s["commonName"] for s in species if not s["imageUrl"]])
    block("NO IMAGE ATTRIBUTION", [s["commonName"] for s in species if not s["imageAttribution"]])
    block("NO HABITAT TEXT AT ALL", [s["commonName"] for s in species if not s["habitatText"]])
    if had_key:
        block("NO XENO-CANTO RECORDING", report["no_call_none_found"])

    text = "\n".join(lines)
    path.write_text(text, encoding="utf-8")
    return text


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", default=str(DEFAULT_OUT), help="path of the generated pacific.json")
    ap.add_argument("--region", default=str(HERE / "region.json"))
    ap.add_argument("--animals", default=str(HERE / "curated_animals.json"))
    ap.add_argument("--plants", default=str(HERE / "curated_plants.json"))
    ap.add_argument("--duke-out", default=None, help="path of the generated duke_ethnobot.json")
    ap.add_argument("--refresh", action="store_true", help="ignore the cache and re-fetch everything")
    ap.add_argument("--only", type=int, default=0, help="build only the first N of each kingdom")
    args = ap.parse_args()

    with open(args.region, encoding="utf-8") as fh:
        region = json.load(fh)
    with open(args.animals, encoding="utf-8") as fh:
        animals_input = json.load(fh)
    with open(args.plants, encoding="utf-8") as fh:
        plants_input = json.load(fh)

    ecosystems = region["ecosystems"]
    ecosystem_ids = {e["id"] for e in ecosystems}
    animal_entries = animals_input["species"]
    plant_entries = plants_input["species"]
    if args.only:
        animal_entries = animal_entries[: args.only]
        plant_entries = plant_entries[: args.only]

    api_key = os.environ.get("XC_API_KEY", "").strip()
    if not api_key:
        print("XC_API_KEY is not set — skipping Xeno-canto; every callUrl will be null.")

    report = defaultdict(list)
    built = []
    failures = []

    for i, entry in enumerate(animal_entries, 1):
        print(f"[animal {i:3d}/{len(animal_entries)}] {entry['commonName']}", flush=True)
        try:
            built.append(build_species(entry, ecosystem_ids, api_key, args.refresh, report))
        except SystemExit:
            raise
        except Exception as exc:
            failures.append(f"{entry['commonName']}: {type(exc).__name__}: {exc}")
            print(f"    !! FAILED: {exc}", file=sys.stderr)

    print("\nbuilding the Duke's ethnobotanical index…", flush=True)
    duke_index, duke_rows = build_duke_index(args.refresh)
    print(f"  {len(duke_index)} taxa from {duke_rows} ETHNOBOT rows", flush=True)

    uses_review = []
    for i, entry in enumerate(plant_entries, 1):
        print(f"[plant  {i:3d}/{len(plant_entries)}] {entry['commonName']}", flush=True)
        try:
            built.append(
                build_plant(entry, ecosystem_ids, duke_index, args.refresh, report, uses_review)
            )
        except SystemExit:
            raise
        except Exception as exc:
            failures.append(f"{entry['commonName']}: {type(exc).__name__}: {exc}")
            print(f"    !! FAILED: {exc}", file=sys.stderr)

    # Kingdom then dex number: the asset reads animals 1..120, then plants 1..80,
    # and the importer applies the per-kingdom base (11.1).
    built.sort(key=lambda s: (0 if s["kingdom"] == "animal" else 1, s["dexNumber"]))

    # Lift the pipeline-only facts out of the records before anything is written.
    internals = {}
    for record in built:
        internals[record["id"]] = {
            "poison": record.pop("_poison", False),
            "distinct": record.pop("_dukeDistinct", 0),
            "recordCount": record.pop("_dukeRecordCount", 0),
            "dukeMatchedName": record.pop("_dukeMatchedName", None),
        }
        record.pop("_curatedNote", None)

    catalogue = {
        "catalogueVersion": region.get("catalogueVersion", 1),
        "regionId": region["regionId"],
        "regionName": region["regionName"],
        "ecosystems": ecosystems,
        "species": built,
    }

    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    review_path = CACHE_DIR / "plant_uses_review.txt"
    review_path.write_text(
        "Wikipedia uses/culinary/edibility/medicinal section text, for CHECKING the\n"
        "hand-written usesNote in curated_plants.json. Never copied into the asset.\n\n"
        + "\n".join(uses_review),
        encoding="utf-8",
    )

    out = Path(args.out).resolve()
    duke_out = Path(args.duke_out) if args.duke_out else out.parent / "duke_ethnobot.json"
    duke_bytes = write_duke_asset(duke_index, duke_out)

    report_text = write_report(
        catalogue, report, CACHE_DIR / "report.txt", bool(api_key), internals, duke_rows, duke_bytes
    )
    print("\n" + report_text)

    if failures:
        print("SPECIES THAT FAILED ENTIRELY:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1

    if not args.only:
        problems = validate(catalogue, ecosystem_ids, internals)
        problems += validate_duke_asset(duke_out, built, internals)
        if problems:
            print("VALIDATION FAILED:", file=sys.stderr)
            for p in problems:
                print(f"  - {p}", file=sys.stderr)
            return 1

    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as fh:
        json.dump(catalogue, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print(f"\nwrote {out} ({len(built)} species)")
    print(f"wrote {duke_out} ({duke_bytes / 1e6:.2f} MB)")
    print(f"report: {CACHE_DIR / 'report.txt'}")
    print(f"uses review: {review_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

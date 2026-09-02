#!/usr/bin/env python3
"""Build the bundled Pacific catalogue asset from the curated species list.

Reads `curated_species.json` (the hand-authored 120 species + ecosystem tags),
enriches each entry from GBIF, Wikipedia/Wikimedia Commons and Xeno-canto, and
writes `app/src/main/assets/catalogue/pacific.json` in the shape ARCHITECTURE.md
section 3.2 specifies.

Usage:
    python3 build_catalogue.py                       # default --out path
    python3 build_catalogue.py --out /some/where.json
    python3 build_catalogue.py --refresh             # ignore the cache
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
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE_DIR = HERE / "cache"
DEFAULT_OUT = HERE / ".." / ".." / "app" / "src" / "main" / "assets" / "catalogue" / "pacific.json"

USER_AGENT = "AnimalDex/1.0 (personal Android app; tlong@unified.health)"

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
        if exc.code != 404:
            print(f"    ! HTTP {exc.code} for {_scrub(url)}", file=sys.stderr)
    except Exception as exc:  # network hiccup, bad JSON, timeout
        print(f"    ! {type(exc).__name__} for {_scrub(url)}: {exc}", file=sys.stderr)
        # Do not cache a transient failure — let the next run retry it.
        return None, False

    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        json.dump({"url": _scrub(url), "payload": payload}, fh)
    return payload, False


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
# Assembly
# --------------------------------------------------------------------------

def slugify(name: str) -> str:
    text = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode("ascii")
    text = text.lower().replace("'", "").replace("’", "")
    text = re.sub(r"[^a-z0-9]+", "-", text)
    return text.strip("-")


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


def validate(catalogue, ecosystem_ids):
    species = catalogue["species"]
    problems = []
    if len(species) != 120:
        problems.append(f"expected 120 species, got {len(species)}")
    numbers = [s["dexNumber"] for s in species]
    if sorted(numbers) != list(range(1, 121)):
        problems.append("dexNumbers are not exactly 1..120 with no duplicates")
    ids = [s["id"] for s in species]
    dupes = [i for i, n in Counter(ids).items() if n > 1]
    if dupes:
        problems.append(f"duplicate species ids: {dupes}")
    valid_classes = {"bird", "mammal", "reptile", "amphibian", "fish", "insect", "other_invertebrate"}
    for s in species:
        for field in ("id", "commonName", "scientificName", "taxClass", "silhouetteRes"):
            if not s.get(field):
                problems.append(f"{s['id']}: missing {field}")
        if s["taxClass"] not in valid_classes:
            problems.append(f"{s['id']}: bad taxClass {s['taxClass']}")
        if s["silhouetteRes"] != f"sil_{s['taxClass']}":
            problems.append(f"{s['id']}: silhouetteRes does not match taxClass")
        if not s["ecosystemIds"]:
            problems.append(f"{s['id']}: no ecosystemIds")
        for eco in s["ecosystemIds"]:
            if eco not in ecosystem_ids:
                problems.append(f"{s['id']}: undeclared ecosystem {eco}")
    return problems


def write_report(catalogue, report, path, had_key):
    species = catalogue["species"]
    lines = []
    add = lines.append
    add("Animal Dex — catalogue build report")
    add(f"generated: {time.strftime('%Y-%m-%d %H:%M:%S')}")
    add(f"HTTP requests made: {HTTP_REQUESTS}   cache hits: {CACHE_HITS}")
    add("")

    total = len(species)
    with_habitat = sum(1 for s in species if s["habitatText"])
    with_image = sum(1 for s in species if s["imageUrl"])
    with_image_attr = sum(1 for s in species if s["imageAttribution"])
    with_call = sum(1 for s in species if s["callUrl"])
    with_desc = sum(1 for s in species if s["description"])
    with_info = sum(1 for s in species if s["infoUrl"])

    add("COVERAGE")
    add(f"  species              {total}")
    add(f"  habitatText          {with_habitat}/{total}  (lede fallback: {len(report['lede_fallback'])})")
    add(f"  description          {with_desc}/{total}")
    add(f"  imageUrl             {with_image}/{total}")
    add(f"  imageAttribution     {with_image_attr}/{total}")
    add(f"  infoUrl              {with_info}/{total}")
    add(f"  callUrl              {with_call}/{total}")
    add("")

    add("CALLS")
    if had_key:
        add(f"  missing because no Xeno-canto recording exists: {len(report['no_call_none_found'])}")
        add("  missing because XC_API_KEY was absent:           0")
    else:
        add("  XC_API_KEY was NOT set — Xeno-canto was skipped entirely.")
        add(f"  missing because XC_API_KEY was absent:           {len(report['no_call_key_absent'])}")
        add("  missing because no Xeno-canto recording exists:  0 (not checked)")
    add("")

    add("CLASS DISTRIBUTION")
    for cls, n in sorted(Counter(s["taxClass"] for s in species).items(), key=lambda kv: -kv[1]):
        add(f"  {cls:20s} {n}")
    add("")

    add("ECOSYSTEM DISTRIBUTION (a species counts in each of its ecosystems)")
    eco_counts = Counter()
    for s in species:
        eco_counts.update(s["ecosystemIds"])
    for eco in catalogue["ecosystems"]:
        add(f"  {eco['name']:28s} {eco_counts[eco['id']]}")
    add("")

    def block(title, items):
        add(f"{title} ({len(items)})")
        for item in items:
            add(f"  - {item}")
        add("")

    block("GBIF MATCHES NEEDING CURATOR REVIEW", report["gbif_warnings"])
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
    ap.add_argument("--input", default=str(HERE / "curated_species.json"))
    ap.add_argument("--refresh", action="store_true", help="ignore the cache and re-fetch everything")
    ap.add_argument("--only", type=int, default=0, help="build only the first N species (for a smoke run)")
    args = ap.parse_args()

    with open(args.input, encoding="utf-8") as fh:
        curated = json.load(fh)

    ecosystems = curated["ecosystems"]
    ecosystem_ids = {e["id"] for e in ecosystems}
    entries = curated["species"]
    if args.only:
        entries = entries[: args.only]

    api_key = os.environ.get("XC_API_KEY", "").strip()
    if not api_key:
        print("XC_API_KEY is not set — skipping Xeno-canto; every callUrl will be null.")

    report = defaultdict(list)
    built = []
    failures = []

    for i, entry in enumerate(entries, 1):
        print(f"[{i:3d}/{len(entries)}] {entry['commonName']}", flush=True)
        try:
            built.append(build_species(entry, ecosystem_ids, api_key, args.refresh, report))
        except SystemExit:
            raise
        except Exception as exc:
            failures.append(f"{entry['commonName']}: {type(exc).__name__}: {exc}")
            print(f"    !! FAILED: {exc}", file=sys.stderr)

    built.sort(key=lambda s: s["dexNumber"])
    catalogue = {
        "catalogueVersion": curated.get("catalogueVersion", 1),
        "regionId": curated["regionId"],
        "regionName": curated["regionName"],
        "ecosystems": ecosystems,
        "species": built,
    }

    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    report_text = write_report(catalogue, report, CACHE_DIR / "report.txt", bool(api_key))
    print("\n" + report_text)

    if failures:
        print("SPECIES THAT FAILED ENTIRELY:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1

    if not args.only:
        problems = validate(catalogue, ecosystem_ids)
        if problems:
            print("VALIDATION FAILED:", file=sys.stderr)
            for p in problems:
                print(f"  - {p}", file=sys.stderr)
            return 1

    out = Path(args.out).resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as fh:
        json.dump(catalogue, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print(f"\nwrote {out} ({len(built)} species)")
    print(f"report: {CACHE_DIR / 'report.txt'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

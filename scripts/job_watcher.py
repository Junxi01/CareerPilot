from __future__ import annotations

"""
Watch user-configured public career pages and create job_leads via the backend API.

Restrictions (by design):
- Only uses careers_url from target companies (active) you configured in the app.
- Refuses LinkedIn, Indeed, Glassdoor, and other obvious blocklisted hosts for both
  the careers page and discovered job URLs.
- Does not handle login walls or JavaScript-only listings; static HTML only.
"""

import argparse
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple
from urllib.parse import urldefrag, urljoin, urlparse

import requests
from bs4 import BeautifulSoup

from scripts.common.api_client import ApiClient, ApiError, get_client
from scripts.common.config import load_env

USER_AGENT = (
    "CareerPilotLocalJobWatcher/1.0 (+local personal tool; no commercial crawling; "
    "repository: careerpilot-local)"
)

# Hosts we never fetch or accept as job targets (user requirement + common aggregators).
_BLOCKED_HOST_SUFFIXES = (
    "linkedin.com",
    "indeed.com",
    "glassdoor.com",
    "glassdoor.co.uk",
    "glassdoor.ie",
    "glassdoor.de",
    "glassdoor.fr",
)

_JOB_PATH_HINT = re.compile(
    r"(job|jobs|career|careers|opening|openings|vacancy|vacancies|"
    r"opportunit|position|requisition|req|listing|role|apply|hiring)",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class ParsedListing:
    title: str
    job_url: str
    snippet: str


def _host_blocked(host: str) -> bool:
    h = (host or "").lower()
    if not h:
        return True
    for suf in _BLOCKED_HOST_SUFFIXES:
        if h == suf or h.endswith("." + suf):
            return True
    return False


def _careers_url_skip_reason(url: str) -> Optional[str]:
    """If this URL must not be fetched, return a short reason; else None."""
    s = (url or "").strip()
    if not s:
        return "empty careers_url"
    try:
        p = urlparse(s)
    except Exception:
        return "invalid URL (parse error)"
    if p.scheme not in ("http", "https"):
        return f"unsupported scheme {p.scheme!r} (only http/https)"
    if _host_blocked(p.netloc):
        return (
            f"unsupported host {p.netloc!r} — LinkedIn, Indeed, Glassdoor "
            f"(and variants) are blocked; login-only job boards are not supported"
        )
    return None


def _careers_url_allowed(url: str) -> bool:
    return _careers_url_skip_reason(url) is None


def _normalize_job_url(url: str) -> str:
    base, _frag = urldefrag(url.strip())
    return base


def _fetch_html(
    session: requests.Session,
    url: str,
    *,
    timeout: float,
) -> Tuple[str, Optional[str]]:
    """Returns (html, error_message). error_message set on failure (one company only; loop continues)."""
    try:
        r = session.get(url, timeout=timeout, allow_redirects=True)
    except requests.Timeout:
        return ("", f"request timeout (exceeded {timeout:g}s); url={url}")
    except requests.RequestException as e:
        return ("", f"network error {type(e).__name__}: {e}; url={url}")

    if r.status_code >= 400:
        reason = (r.reason or "").strip()
        return ("", f"HTTP {r.status_code}{(' ' + reason) if reason else ''}; url={url}")

    enc = r.apparent_encoding or r.encoding or "utf-8"
    return (r.content.decode(enc, errors="replace"), None)


def _read_mock_html(path: Path) -> Tuple[str, Optional[str]]:
    try:
        return (path.read_text(encoding="utf-8", errors="replace"), None)
    except OSError as e:
        return ("", f"{type(e).__name__}: {e}")


def _same_site_or_trusted(base: str, job_url: str) -> bool:
    try:
        b = urlparse(base)
        j = urlparse(job_url)
    except Exception:
        return False
    if j.scheme not in ("http", "https"):
        return False
    if _host_blocked(j.netloc):
        return False
    return (b.netloc or "").lower() == (j.netloc or "").lower()


def _looks_like_job_href(href: str, link_text: str, base_url: str) -> bool:
    if not href or href.startswith(("#", "javascript:", "mailto:", "tel:")):
        return False
    joined = urljoin(base_url.rstrip("/") + "/", href)
    try:
        p = urlparse(joined)
    except Exception:
        return False
    if p.scheme not in ("http", "https"):
        return False
    if _host_blocked(p.netloc):
        return False
    path_q = f"{p.path} {p.query}"
    if _JOB_PATH_HINT.search(path_q):
        return True
    if _JOB_PATH_HINT.search(link_text or ""):
        return True
    # Same-site detail pages are often ambiguous; keep if careers base path matches subtree.
    base_p = urlparse(base_url)
    if (p.netloc or "").lower() == (base_p.netloc or "").lower() and p.path.startswith(base_p.path.rstrip("/")):
        # Require some non-trivial slug so we skip header/footer anchors.
        if len(p.path.strip("/")) > len(base_p.path.strip("/")) + 5:
            return True
    return False


def extract_job_listings(html: str, *, page_url: str) -> List[ParsedListing]:
    """
    Best-effort parse: malformed or unusual HTML should not crash the watcher.
    Relative hrefs are resolved with urljoin against page_url.
    """
    results: List[ParsedListing] = []
    seen: Set[str] = set()
    try:
        soup = BeautifulSoup(html or "", "html.parser")
    except Exception as e:
        print(f"[job_watcher] PARSE ERROR (BeautifulSoup): {type(e).__name__}: {e}", file=sys.stderr)
        return []

    try:
        anchors = soup.find_all("a", href=True)
    except Exception as e:
        print(f"[job_watcher] PARSE ERROR (scan anchors): {type(e).__name__}: {e}", file=sys.stderr)
        return []

    base_for_join = page_url.rstrip("/") + "/"
    for a in anchors:
        try:
            href = str(a.get("href") or "").strip()
            title = " ".join((a.get_text() or "").split())
            if len(title) < 2:
                continue
            if not _looks_like_job_href(href, title, page_url):
                continue
            # Resolve relative and protocol-relative URLs to absolute http(s).
            absolute = _normalize_job_url(urljoin(base_for_join, href))
            if absolute in seen:
                continue
            try:
                parsed = urlparse(absolute)
                if parsed.scheme not in ("http", "https") or _host_blocked(parsed.netloc):
                    continue
            except Exception:
                continue
            if not _same_site_or_trusted(page_url, absolute):
                continue
            parent = a.parent
            snippet = ""
            if parent is not None:
                try:
                    snippet = " ".join(parent.get_text(" ", strip=True).split())[:2000]
                except Exception:
                    snippet = ""
            seen.add(absolute)
            results.append(ParsedListing(title=title[:500], job_url=absolute, snippet=snippet))
        except Exception as e:
            print(f"[job_watcher] PARSE WARN (skip one link): {type(e).__name__}: {e}", file=sys.stderr)
            continue

    return results


def _score_listing(
    listing: ParsedListing,
    keywords: List[str],
    locations: List[str],
) -> Tuple[float, List[str], Optional[str]]:
    """Returns (match_score 0..100, matched_keywords, inferred location). Keyword / location match is case-insensitive."""
    blob = f"{listing.title} {listing.snippet} {listing.job_url}".lower()
    matched_kw: List[str] = []
    for kw in keywords:
        k = kw.strip()
        if not k:
            continue
        if k.lower() in blob:
            matched_kw.append(k)

    loc_hits = 0
    inferred: Optional[str] = None
    for loc in locations:
        l = loc.strip()
        if not l:
            continue
        if l.lower() in blob:
            loc_hits += 1
            inferred = inferred or l

    n_kw = max(len([k for k in keywords if k.strip()]), 1)
    keyword_part = min(70.0, (len(matched_kw) / n_kw) * 70.0)

    if not locations or not any(loc.strip() for loc in locations):
        location_part = 30.0
    else:
        n_loc = max(len([l for l in locations if l.strip()]), 1)
        location_part = min(30.0, (loc_hits / n_loc) * 30.0)

    score = min(100.0, round(keyword_part + location_part, 2))
    return score, matched_kw, inferred


def _load_target_companies(client: ApiClient) -> List[Dict[str, Any]]:
    data = client.get("/api/target-companies")
    if not isinstance(data, list):
        raise RuntimeError("Unexpected /api/target-companies response shape")
    return [c for c in data if isinstance(c, dict)]


def _load_existing_job_urls(client: ApiClient) -> Set[str]:
    data = client.get("/api/job-leads")
    if not isinstance(data, list):
        raise RuntimeError("Unexpected /api/job-leads response shape")
    out: Set[str] = set()
    for row in data:
        if not isinstance(row, dict):
            continue
        u = row.get("job_url")
        if isinstance(u, str) and u.strip():
            out.add(_normalize_job_url(u.strip()))
    return out


def _post_lead(client: ApiClient, body: Dict[str, Any], *, dry_run: bool) -> str:
    if dry_run:
        return "dry-run"
    try:
        client.post("/api/job-leads", body)
        return "created"
    except ApiError as e:
        if e.code == "duplicate_job_url":
            return "duplicate"
        raise


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Fetch public career pages from active target companies and store job leads via API.",
    )
    p.add_argument("--dry-run", action="store_true", help="Parse and score; print actions; no POST.")
    p.add_argument(
        "--company-id",
        type=int,
        default=None,
        metavar="ID",
        help="Only process the target company with this id.",
    )
    p.add_argument(
        "--delay-seconds",
        type=float,
        default=2.0,
        help="Pause between HTTP fetches per company (default: 2). Ignored when using --mock-html.",
    )
    p.add_argument(
        "--timeout",
        type=float,
        default=30.0,
        help="HTTP timeout seconds per careers page.",
    )
    p.add_argument(
        "--mock-html",
        type=str,
        default="",
        help="Use this HTML file instead of HTTP for every company’s careers page (parser tests). "
        "`page_url` for resolving relatives defaults to https://example-careers.test/careers",
    )
    p.add_argument(
        "--mock-page-url",
        type=str,
        default="https://example-careers.test/careers/",
        help="Base URL used with --mock-html for resolving relative links.",
    )
    p.add_argument(
        "--min-score",
        type=float,
        default=0.0,
        help="Only create leads when match_score is >= this threshold (default: 0).",
    )
    return p.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    load_env()
    args = parse_args(argv)
    delay = max(0.0, float(args.delay_seconds))
    timeout = max(1.0, float(args.timeout))
    min_score = float(args.min_score)

    mock_path = Path(args.mock_html).expanduser() if str(args.mock_html).strip() else None
    if mock_path is not None and not mock_path.is_file():
        print(f"[job_watcher] --mock-html not a file: {mock_path}", file=sys.stderr)
        return 2

    client = get_client()
    companies = _load_target_companies(client)

    for c in companies:
        if c.get("active") is not True:
            continue
        raw = str(c.get("careers_url") or "").strip()
        if not raw:
            continue
        skip = _careers_url_skip_reason(raw)
        if skip:
            print(
                f"[job_watcher] SKIP company_id={c.get('id')} name={str(c.get('name') or '')!r} "
                f"reason: {skip}; url={raw}",
                file=sys.stderr,
            )

    active = [
        c
        for c in companies
        if c.get("active") is True
        and str(c.get("careers_url") or "").strip()
        and _careers_url_allowed(str(c.get("careers_url")))
    ]

    if args.company_id is not None:
        wid = int(args.company_id)

        def _wid(c: Dict[str, Any]) -> Optional[int]:
            v = c.get("id")
            if v is None:
                return None
            try:
                return int(v)
            except (TypeError, ValueError):
                return None

        active = [c for c in active if _wid(c) == wid]
        if not active:
            print(f"[job_watcher] No active target company with id={args.company_id}.", file=sys.stderr)
            return 1

    if not active:
        print("[job_watcher] No active target companies with allowed public careers_url.", file=sys.stderr)
        return 0

    if args.dry_run:
        print("[job_watcher] dry-run: will not POST /api/job-leads (no new rows written).", file=sys.stderr)

    existing_urls = _load_existing_job_urls(client)

    session = requests.Session()
    session.headers.update(
        {
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.5",
        }
    )

    created = skipped_dup = skipped_score = fetch_errors = 0

    for idx, co in enumerate(active):
        if idx > 0 and mock_path is None:
            time.sleep(delay)

        cid_raw = co.get("id")
        if cid_raw is None:
            print(f"[job_watcher] SKIP target company missing id field name={co.get('name')!r}", file=sys.stderr)
            continue
        try:
            cid = int(cid_raw)
        except (TypeError, ValueError):
            print(f"[job_watcher] SKIP invalid company id={cid_raw!r}", file=sys.stderr)
            continue
        name = str(co.get("name") or "").strip()
        careers_url = str(co.get("careers_url") or "").strip()
        keywords = co.get("keywords") or []
        locations = co.get("locations") or []

        kw_list = [str(x) for x in keywords] if isinstance(keywords, list) else []
        loc_list = [str(x) for x in locations] if isinstance(locations, list) else []

        if mock_path is not None:
            html, err = _read_mock_html(mock_path)
            page_url = str(args.mock_page_url).strip() or "https://example-careers.test/careers/"
        else:
            html, err = _fetch_html(session, careers_url, timeout=timeout)
            page_url = careers_url

        if err:
            fetch_errors += 1
            print(f"[job_watcher] FETCH ERROR company_id={cid} name={name!r} url={careers_url} — {err}", file=sys.stderr)
            continue

        listings = extract_job_listings(html, page_url=page_url)
        print(f"[job_watcher] company_id={cid} {name!r}: extracted {len(listings)} plausible job links")

        for listing in listings:
            jurl = _normalize_job_url(listing.job_url)
            if jurl in existing_urls:
                skipped_dup += 1
                continue

            score, matched, loc_hint = _score_listing(listing, kw_list, loc_list)
            if score < min_score:
                skipped_score += 1
                continue

            body: Dict[str, Any] = {
                "company_id": cid,
                "role_title": listing.title,
                "job_url": jurl,
                "location": loc_hint,
                "raw_description": listing.snippet or None,
                "matched_keywords": matched,
                "match_score": score,
                "saved_to_applications": False,
            }

            label = (
                f"company_id={cid} score={score} title={listing.title[:80]!r} "
                f"keywords_matched={matched} url={jurl}"
            )
            try:
                res = _post_lead(client, body, dry_run=bool(args.dry_run))
            except ApiError as e:
                print(f"[job_watcher] API ERROR POST {label} — {e}", file=sys.stderr)
                continue

            if res == "created":
                created += 1
                existing_urls.add(jurl)
                print(f"[job_watcher] created lead: {label}")
            elif res == "duplicate":
                skipped_dup += 1
                existing_urls.add(jurl)
                print(f"[job_watcher] skip duplicate job_url: {jurl}")
            else:
                print(f"[job_watcher] dry-run would POST: {label}")

    print(
        f"[job_watcher] done: created={created} skipped_duplicates={skipped_dup} "
        f"skipped_low_score={skipped_score} fetch_errors={fetch_errors}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

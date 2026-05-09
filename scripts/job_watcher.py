from __future__ import annotations

"""
Watch user-configured career-related pages and discover job postings via polite same-site crawl.

Behavior:
- Default: breadth-first-ish crawl from careers_url ("seed"), prioritizing URLs that look like
  careers/jobs/ATS portals. Many brand-only pages work better because we follow high-scoring links.
- Optionally extract JobPosting entries from embedded JSON-LD (when present).

Hard limits still apply:
- Blocklist: LinkedIn, Indeed, Glassdoor (configured careers_url AND discovered/job URLs).
- Single-page `--no-discovery` mode for deterministic tests.
- No login / CAPTCHA bypass. Truly JS-only SPAs still need rendered HTML — use optional Playwright/Selenium
  outside this repo if you hit empty pages.

ATS hosts (exact job postings may still be blocked if URL host is aggregated): commonly allowed hosts
following a link FROM your seed domain, e.g. boards.*.greenhouse.io, jobs.lever.co.
"""

import argparse
import heapq
import json
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
    "CareerPilotLocalJobWatcher/1.1 (+local personal tool; career discovery crawl; repository: careerpilot-local)"
)

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
    r"opportunit|position|requisition|req|listing|role|apply|hiring|talent|vacancy|rider)",
    re.IGNORECASE,
)

_EXTRA_PATH_HINTS = re.compile(
    r"(join[-_]?team|browse[-_]?jobs|search[-_]?jobs|open[-_]?positions|work[-_]?with[-_]?us|"
    r"employment|life[-_]at|rider|students|internships?|graduate)",
    re.IGNORECASE,
)

_NEGATIVE_PATH_HINT = re.compile(
    r"(/privacy|/cookie|/legal|/terms|/imprint|mailto:|instagram\.com|facebook\.com|twitter\.com|x\.com|/static/|/assets/)",
    re.IGNORECASE,
)

_ASSET_PATH_HINT = re.compile(
    r"\.(?:css|js|mjs|map|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|otf)(?:$|[?#])|/(?:static|assets|images|img|fonts)/",
    re.IGNORECASE,
)

_JOB_ID_HINT = re.compile(
    r"(\d{4,}|[0-9a-f]{8}-[0-9a-f-]{12,}|/job/|/jobs/|/positions/|/openings/|gh_jid=|jobid=|job_id=|requisition)",
    re.IGNORECASE,
)

_STRONG_ROLE_HINT = re.compile(
    r"(software|engineer|developer|designer|manager|analyst|scientist|director|lead|specialist|product|data|security|sales|marketing|finance|recruit|intern|graduate|requisition|opening|position|role)",
    re.IGNORECASE,
)

_ATS_REGISTERED_SUFFIXES = (
    "greenhouse.io",
    "lever.co",
    "smartrecruiters.com",
    "workday.com",
    "myworkdayjobs.com",
    "icims.com",
    "teamtailor.com",
    "ashbyhq.com",
    "workable.com",
    "bamboohr.com",
    "oraclecloud.com",
    "oracle.com",
    "jobvite.com",
    "successfactors.com",
    "successfactors.eu",
    "taleo.net",
    "eightfold.ai",
    "rippling-ats.com",
    "pinpointhq.com",
    "recruiting.com",
    "recruitee.com",
    "breezyhr.com",
    "applytojob.com",
    "jobs.personio.de",
)


@dataclass(frozen=True)
class ParsedListing:
    title: str
    job_url: str
    snippet: str


def _host_blocked(host: str) -> bool:
    h = (host or "").lower().split(":")[0]
    if not h:
        return True
    for suf in _BLOCKED_HOST_SUFFIXES:
        if h == suf or h.endswith("." + suf):
            return True
    return False


def _norm_host_piece(h: str) -> str:
    return (h or "").lower().split(":")[0].lstrip(".").strip()


def _hosts_organizationally_related(seed_host: str, other_host: str) -> bool:
    """Seed careers.example.com may link jobs.example.com (subdomain sibling)."""
    a, b = _norm_host_piece(seed_host), _norm_host_piece(other_host)
    if not a or not b:
        return False
    return a == b or a.endswith("." + b) or b.endswith("." + a)


def _known_ats_host(host: str) -> bool:
    h = _norm_host_piece(host)
    if not h:
        return False
    if _host_blocked(h):
        return False
    for suf in _ATS_REGISTERED_SUFFIXES:
        if h == suf or h.endswith("." + suf):
            return True
    return False


def _job_destination_allowed(seed_careers_url: str, job_url: str) -> bool:
    try:
        s = urlparse(seed_careers_url).netloc
        j_p = urlparse(job_url)
    except Exception:
        return False
    j_host = j_p.netloc.split(":")[0]
    if j_p.scheme not in ("http", "https"):
        return False
    if _host_blocked(j_host):
        return False
    if _hosts_organizationally_related(s, j_host):
        return True
    if _known_ats_host(j_host):
        return True
    return False


def _careers_url_skip_reason(url: str) -> Optional[str]:
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
    return base.replace("\\/", "/").replace("\\u002F", "/").replace("\\u003A", ":")


def _title_from_url(url: str) -> str:
    try:
        p = urlparse(url)
        last = (p.path or "").strip("/").split("/")[-1]
    except Exception:
        last = url.strip().split("/")[-1]
    title = re.sub(r"^\d+[-_]?", "", last)
    title = re.sub(r"[-_+]+", " ", title)
    title = re.sub(r"\s+", " ", title).strip()
    return title or "Job posting"


def _fetch_html(
    session: requests.Session,
    url: str,
    *,
    timeout: float,
) -> Tuple[str, Optional[str]]:
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


def _discovery_candidate_score(abs_url: str, anchor_text: str) -> float:
    """How likely resolving this URL gets us nearer to listings (0–100 scale, soft)."""
    try:
        p = urlparse(abs_url)
    except Exception:
        return 0.0
    pq = (p.path + "?" + (p.query or "")).lower()
    blob = pq + " " + (anchor_text or "").lower()
    score = 0.0
    if _known_ats_host(p.netloc):
        score += 55.0
    if _JOB_PATH_HINT.search(pq):
        score += 42.0
    if _EXTRA_PATH_HINTS.search(pq):
        score += 22.0
    if _JOB_PATH_HINT.search(anchor_text or ""):
        score += 34.0
    if _EXTRA_PATH_HINTS.search(anchor_text or ""):
        score += 22.0
    if "/search" in pq and ("job" in pq or "career" in pq or "position" in pq):
        score += 18.0
    if _NEGATIVE_PATH_HINT.search(pq) or _NEGATIVE_PATH_HINT.search(anchor_text or ""):
        score -= 60.0
    return max(0.0, min(100.0, score))


def _follow_discovery_eligible(seed_host: str, target_url: str) -> bool:
    try:
        t_host = urlparse(target_url).netloc.split(":")[0]
    except Exception:
        return False
    if _host_blocked(t_host):
        return False
    return _hosts_organizationally_related(seed_host, t_host) or _known_ats_host(t_host)


def extract_jsonld_job_postings(html: str, *, seed_careers_url: str, page_url: str) -> List[ParsedListing]:
    out: List[ParsedListing] = []
    seen: Set[str] = set()
    try:
        soup = BeautifulSoup(html or "", "html.parser")
        for script in soup.find_all("script", attrs={"type": re.compile(r"ld\+json", re.I)}):
            raw = script.string or script.get_text() or ""
            if not raw.strip():
                continue
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                continue
            stack = [data]
            while stack:
                node = stack.pop()
                if isinstance(node, dict):
                    types = node.get("@type")
                    tlist = types if isinstance(types, list) else [types]
                    tl = []
                    for t in tlist:
                        if isinstance(t, str):
                            tl.append(t)
                    lower_types = [x.lower() for x in tl if isinstance(x, str)]
                    if any("jobposting" in x for x in lower_types):
                        title = (
                            node.get("title")
                            or node.get("name")
                            or node.get("headline")
                            or "Job posting"
                        )
                        if isinstance(title, dict):
                            title = title.get("@value") or title.get("value") or ""
                        title = str(title).strip()
                        hu = (
                            node.get("url")
                            or (node.get("sameAs")[0] if isinstance(node.get("sameAs"), list) else node.get("sameAs"))
                            or node.get("@id")
                        )
                        if isinstance(hu, list) and hu:
                            hu = hu[0]
                        job_url_str = ""
                        if isinstance(hu, str):
                            job_url_str = hu.strip()
                        if not title:
                            title = "Job posting"
                        if job_url_str:
                            job_url_abs = _normalize_job_url(urljoin(page_url.rstrip("/") + "/", job_url_str))
                            if _job_destination_allowed(seed_careers_url, job_url_abs) and job_url_abs not in seen:
                                desc = ""
                                jd = node.get("description")
                                if isinstance(jd, str):
                                    desc = jd[:2000]
                                elif isinstance(jd, dict):
                                    desc = str(jd.get("@value") or jd.get("text") or "")[:2000]
                                loc = ""
                                pl = node.get("jobLocation")
                                if isinstance(pl, dict):
                                    loc = (
                                        str(pl.get("name") or pl.get("address", {}).get("addressLocality") or "")
                                        if isinstance(pl.get("address"), dict)
                                        else str(pl.get("name") or "")
                                    )
                                preview = (" ".join(f"{loc} {desc}".split()))[:2000]
                                seen.add(job_url_abs)
                                out.append(
                                    ParsedListing(
                                        title=title[:500],
                                        job_url=job_url_abs,
                                        snippet=preview,
                                    ),
                                )
                    for _k, v in node.items():
                        if isinstance(v, (dict, list)):
                            stack.append(v)
                elif isinstance(node, list):
                    for it in node:
                        if isinstance(it, (dict, list)):
                            stack.append(it)
    except Exception as e:
        print(f"[job_watcher] JSON-LD WARN: {type(e).__name__}: {e}", file=sys.stderr)

    return out


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
    if _ASSET_PATH_HINT.search(p.path or ""):
        return False
    path_q = f"{p.path} {p.query}"
    if _JOB_PATH_HINT.search(path_q):
        return True
    if (link_text or "").strip().lower() != "job posting" and _JOB_PATH_HINT.search(link_text or ""):
        return True
    if _EXTRA_PATH_HINTS.search(path_q) or _EXTRA_PATH_HINTS.search(link_text or ""):
        return True
    base_p = urlparse(base_url)
    if (p.netloc or "").lower() == (base_p.netloc or "").lower() and p.path.startswith(
        base_p.path.rstrip("/"),
    ):
        if len(p.path.strip("/")) > len(base_p.path.strip("/")) + 5:
            return True
    return False


def _generic_job_navigation(url: str, title: str) -> bool:
    generic_titles = {
        "career",
        "careers",
        "jobs",
        "open roles",
        "open positions",
        "view jobs",
        "all jobs",
        "job openings",
        "see open roles",
        "life at stripe",
        "life at",
        "benefits",
        "university",
        "culture",
        "how we operate",
        "our opportunity",
        "apply",
        "apply now",
        "learn more",
        "english",
    }
    try:
        path = (urlparse(url).path or "").strip("/").lower()
    except Exception:
        path = ""
    t = (title or "").strip().lower()
    shallow = len([x for x in path.split("/") if x]) <= 1 and ("career" in path or path == "jobs")
    weak_jobs_path = (path.startswith("jobs/") or path.endswith("/jobs")) and not _JOB_ID_HINT.search(path) and not _STRONG_ROLE_HINT.search(f"{path} {t}")
    return (
        t in generic_titles
        or t.endswith(" open roles")
        or (
            t in generic_titles
            and (path in {"career", "careers", "jobs", "company/careers"} or path.endswith("/careers") or path.endswith("/jobs"))
        )
        or (shallow and not _JOB_ID_HINT.search(path))
        or weak_jobs_path
    )


def _script_url_candidates(html: str) -> List[str]:
    normalized = (
        (html or "")
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u003A", ":")
        .replace("\\u0026", "&")
    )
    urls = re.findall(r"https?://[^\s\"'<>\\\]\)},;]+", normalized, flags=re.IGNORECASE)
    rels = re.findall(
        r"(?<![A-Za-z0-9])/(?:jobs?|careers?|positions?|openings?|requisitions?|req|apply)/[A-Za-z0-9/_.,~:%?&=+#-]{5,}",
        normalized,
        flags=re.IGNORECASE,
    )
    return urls + rels


def _snippet_near(raw: str, needle: str, max_len: int = 2000) -> str:
    if not raw or not needle:
        return ""
    i = raw.find(needle)
    if i < 0:
        return ""
    s = raw[max(0, i - 700) : min(len(raw), i + len(needle) + 700)]
    return " ".join(BeautifulSoup(s, "html.parser").get_text(" ", strip=True).split())[:max_len]


def extract_job_listings(
    html: str,
    *,
    page_url: str,
    seed_careers_url: str,
) -> List[ParsedListing]:
    results: List[ParsedListing] = []
    seen: Set[str] = set()
    base_for_join = page_url.rstrip("/") + "/"
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

    for a in anchors:
        try:
            href = str(a.get("href") or "").strip()
            title = " ".join((a.get_text() or "").split())
            if len(title) < 2:
                title = str(a.get("aria-label") or a.get("title") or a.get("data-title") or "").strip()
            if len(title) < 2:
                title = _title_from_url(href)
            if len(title) < 2:
                continue
            if not _looks_like_job_href(href, title, page_url):
                continue
            absolute = _normalize_job_url(urljoin(base_for_join, href))
            if absolute in seen:
                continue
            try:
                parsed = urlparse(absolute)
                if parsed.scheme not in ("http", "https") or _host_blocked(parsed.netloc.split(":")[0]):
                    continue
            except Exception:
                continue
            if not _job_destination_allowed(seed_careers_url, absolute):
                continue
            if _generic_job_navigation(absolute, title):
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

    for ld in extract_jsonld_job_postings(html, seed_careers_url=seed_careers_url, page_url=page_url):
        if ld.job_url not in seen:
            seen.add(ld.job_url)
            results.append(ld)

    for cand in _script_url_candidates(html):
        absolute = _normalize_job_url(urljoin(base_for_join, cand))
        if absolute in seen:
            continue
        try:
            parsed = urlparse(absolute)
            if parsed.scheme not in ("http", "https") or _host_blocked(parsed.netloc.split(":")[0]):
                continue
        except Exception:
            continue
        title = _title_from_url(absolute)
        if not _looks_like_job_href(absolute, title, page_url):
            continue
        if not _job_destination_allowed(seed_careers_url, absolute):
            continue
        if _generic_job_navigation(absolute, title):
            continue
        seen.add(absolute)
        results.append(ParsedListing(title=title[:500], job_url=absolute, snippet=_snippet_near(html, cand)))

    return results


def _ats_api_listings(session: requests.Session, page_url: str, *, timeout: float) -> List[ParsedListing]:
    try:
        p = urlparse(page_url)
    except Exception:
        return []
    host = (p.netloc or "").lower().split(":")[0]
    if host == "jobs.ashbyhq.com" or host.endswith(".ashbyhq.com"):
        return _ashby_api_listings(session, p, timeout=timeout)
    if host.endswith("myworkdayjobs.com"):
        return _workday_api_listings(session, p, timeout=timeout)
    if host in {"jobs.smartrecruiters.com", "careers.smartrecruiters.com"}:
        return _smartrecruiters_api_listings(session, p, timeout=timeout)
    return []


def _ashby_api_listings(session: requests.Session, parsed, *, timeout: float) -> List[ParsedListing]:
    slug = (parsed.path or "").strip("/").split("/")[0]
    if not slug:
        return []
    url = f"https://api.ashbyhq.com/posting-api/job-board/{slug}?includeCompensation=false"
    try:
        data = session.get(url, timeout=timeout, headers={"Accept": "application/json"}).json()
    except Exception:
        return []
    out: List[ParsedListing] = []
    for job in data.get("jobs") or []:
        if not isinstance(job, dict):
            continue
        title = str(job.get("title") or "").strip()
        jid = str(job.get("id") or "").strip()
        job_url = str(job.get("jobUrl") or job.get("url") or "").strip()
        if not job_url and jid:
            job_url = f"https://jobs.ashbyhq.com/{slug}/{jid}"
        if not title or not job_url:
            continue
        loc = str(job.get("location") or "").strip()
        out.append(ParsedListing(title=title[:500], job_url=_normalize_job_url(job_url), snippet=f"{title} {loc}".strip()))
    return out


def _workday_api_listings(session: requests.Session, parsed, *, timeout: float) -> List[ParsedListing]:
    host = (parsed.netloc or "").split(":")[0]
    tenant = host.split(".")[0]
    site = (parsed.path or "").strip("/").split("/")[0]
    if not tenant or not site:
        return []
    url = f"https://{host}/wday/cxs/{tenant}/{site}/jobs"
    try:
        r = session.post(
            url,
            json={"appliedFacets": {}, "limit": 20, "offset": 0, "searchText": ""},
            timeout=timeout,
            headers={"Accept": "application/json"},
        )
        data = r.json()
    except Exception:
        return []
    out: List[ParsedListing] = []
    for job in data.get("jobPostings") or []:
        if not isinstance(job, dict):
            continue
        title = str(job.get("title") or "").strip()
        external = str(job.get("externalPath") or "").strip()
        if not title or not external:
            continue
        loc = str(job.get("locationsText") or "").strip()
        out.append(ParsedListing(title=title[:500], job_url=_normalize_job_url(f"https://{host}{external}"), snippet=f"{title} {loc}".strip()))
    return out


def _smartrecruiters_api_listings(session: requests.Session, parsed, *, timeout: float) -> List[ParsedListing]:
    company = (parsed.path or "").strip("/").split("/")[0]
    if not company:
        return []
    url = f"https://api.smartrecruiters.com/v1/companies/{company}/postings?limit=100"
    try:
        data = session.get(url, timeout=timeout, headers={"Accept": "application/json"}).json()
    except Exception:
        return []
    out: List[ParsedListing] = []
    for job in data.get("content") or []:
        if not isinstance(job, dict):
            continue
        title = str(job.get("name") or "").strip()
        jid = str(job.get("id") or job.get("uuid") or "").strip()
        ref = job.get("ref") if isinstance(job.get("ref"), dict) else {}
        job_url = str(ref.get("jobAd") or "").strip() if isinstance(ref, dict) else ""
        if not job_url and jid:
            job_url = f"https://jobs.smartrecruiters.com/{company}/{jid}"
        if not title or not job_url:
            continue
        loc = job.get("location") if isinstance(job.get("location"), dict) else {}
        loc_s = ", ".join(str(loc.get(k) or "").strip() for k in ("city", "region", "country") if str(loc.get(k) or "").strip())
        out.append(ParsedListing(title=title[:500], job_url=_normalize_job_url(job_url), snippet=f"{title} {loc_s}".strip()))
    return out


def _collect_discovery_links(html: str, page_url: str, seed_host: str) -> List[Tuple[float, str]]:
    """Yield (priority, normalized_url) for enqueue."""
    cand: List[Tuple[float, str]] = []
    try:
        soup = BeautifulSoup(html or "", "html.parser")
        for a in soup.find_all("a", href=True):
            href = str(a.get("href") or "").strip()
            if not href or href.startswith(("#", "javascript:", "mailto:", "tel:")):
                continue
            txt = " ".join((a.get_text() or "").split())
            abs_u = _normalize_job_url(urljoin(page_url.rstrip("/") + "/", href))
            try:
                p = urlparse(abs_u)
            except Exception:
                continue
            if p.scheme not in ("http", "https"):
                continue
            if not _follow_discovery_eligible(seed_host, abs_u):
                continue
            sc = _discovery_candidate_score(abs_u, txt)
            if sc <= 0:
                continue
            cand.append((sc, abs_u))
        for raw_u in _script_url_candidates(html):
            abs_u = _normalize_job_url(urljoin(page_url.rstrip("/") + "/", raw_u))
            try:
                p = urlparse(abs_u)
            except Exception:
                continue
            if p.scheme not in ("http", "https"):
                continue
            if not _follow_discovery_eligible(seed_host, abs_u):
                continue
            sc = _discovery_candidate_score(abs_u, _title_from_url(abs_u))
            if sc <= 0:
                continue
            cand.append((sc, abs_u))
    except Exception as e:
        print(f"[job_watcher] DISCOVERY WARN: {type(e).__name__}: {e}", file=sys.stderr)
    return cand


def crawl_careers_site(
    session: requests.Session,
    *,
    seed_url: str,
    timeout: float,
    delay_seconds: float,
    max_pages: int,
    max_depth: int,
    min_discovery_score: float,
) -> Tuple[List[ParsedListing], int, List[str]]:
    """
    Priority crawl from seed_url. Returns (listings merged unique by job URL, fetch_error_count, log_lines).

    Listing extraction uses seed_url for ATS/org relationship checks across all fetched pages.
    """
    logs: List[str] = []
    seed_host = urlparse(seed_url).netloc.split(":")[0].lower().lstrip("@")
    seen_pages: Set[str] = set()
    seen_job_urls: Set[str] = set()
    merged: List[ParsedListing] = []
    errs = 0
    heap: List[Tuple[float, int, str]] = []
    # (-priority_fetch, depth, url) priority queue: shallow depth wins tie when pri inverted below
    first_push = (-1000.0, 0, _normalize_job_url(seed_url))
    heapq.heappush(heap, first_push)

    while heap and len(seen_pages) < max_pages:
        neg_pri, depth, raw_u = heapq.heappop(heap)
        u = _normalize_job_url(raw_u)
        if u in seen_pages:
            continue

        page_idx = len(seen_pages) + 1
        logs.append(f"fetch[{page_idx}/{max_pages}] depth={depth} url={u}")
        if page_idx > 1 and delay_seconds > 0:
            time.sleep(delay_seconds)

        html, err = _fetch_html(session, u, timeout=timeout)
        if err:
            errs += 1
            logs.append(f"FETCH_ERR {err}")
            continue

        seen_pages.add(u)

        for L in _ats_api_listings(session, u, timeout=timeout):
            if L.job_url not in seen_job_urls:
                seen_job_urls.add(L.job_url)
                merged.append(L)

        listings = extract_job_listings(html, page_url=u, seed_careers_url=seed_url)
        for L in listings:
            if L.job_url not in seen_job_urls:
                seen_job_urls.add(L.job_url)
                merged.append(L)

        if depth < max_depth and len(seen_pages) < max_pages:
            for prio, nu in sorted(
                _collect_discovery_links(html, u, seed_host),
                key=lambda x: (-x[0], x[1]),
            )[:180]:
                if prio < min_discovery_score:
                    continue
                nu2 = _normalize_job_url(nu)
                if nu2 in seen_pages:
                    continue
                # Penalize deep stacks slightly so ATS found early wins
                next_depth = depth + 1
                adjusted = prio - next_depth * 2.5
                heapq.heappush(heap, (-adjusted, next_depth, nu2))

        if len(heap) > 280:
            tmp = heapq.nsmallest(150, heap)
            heap.clear()
            for item in sorted(tmp):
                heapq.heappush(heap, item)

    logs.append(f"discovery summary: fetched_pages={len(seen_pages)} unique_job_links_found={len(merged)} fetch_errors={errs}")
    return merged, errs, logs


def _score_listing(
    listing: ParsedListing,
    keywords: List[str],
    locations: List[str],
) -> Tuple[float, List[str], Optional[str]]:
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
        n_loc = max(len([lo for lo in locations if lo.strip()]), 1)
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
        description="Discover public career pages from target companies (polite crawl) and sync job leads via API.",
    )
    p.add_argument("--dry-run", action="store_true", help="Parse and score; print actions; no POST.")
    p.add_argument("--company-id", type=int, default=None, metavar="ID")
    p.add_argument(
        "--delay-seconds",
        type=float,
        default=2.0,
        help="Pause between page fetches (default: 2). Ignored for --mock-html.",
    )
    p.add_argument("--timeout", type=float, default=30.0)
    p.add_argument(
        "--mock-html",
        type=str,
        default="",
        help="Offline HTML fixture; disables network discovery (single page).",
    )
    p.add_argument(
        "--mock-page-url",
        type=str,
        default="https://example-careers.test/careers/",
    )
    p.add_argument("--min-score", type=float, default=0.0)
    p.add_argument(
        "--no-discovery",
        action="store_true",
        help="Only parse the configured careers_url (no following links).",
    )
    p.add_argument(
        "--max-discovery-pages",
        type=int,
        default=12,
        help="Max HTML pages to fetch per company (default: 12).",
    )
    p.add_argument(
        "--max-discovery-depth",
        type=int,
        default=3,
        help="Max link-hops from seed URL (default: 3).",
    )
    p.add_argument(
        "--min-discovery-score",
        type=float,
        default=15.0,
        help="Minimum heuristic score to enqueue a discovered URL (default: 15).",
    )
    p.add_argument(
        "--verbose-discovery",
        action="store_true",
        help="Print each fetched discovery URL to stderr.",
    )
    return p.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    load_env()
    args = parse_args(argv)
    delay = max(0.0, float(args.delay_seconds))
    timeout = max(1.0, float(args.timeout))
    min_score = float(args.min_score)
    max_pages = max(1, int(args.max_discovery_pages))
    max_depth = max(0, int(args.max_discovery_depth))
    min_disc = max(0.0, float(args.min_discovery_score))

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
        },
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
            if err:
                fetch_errors += 1
                print(f"[job_watcher] MOCK READ ERROR company_id={cid} — {err}", file=sys.stderr)
                continue
            listings = extract_job_listings(html, page_url=page_url, seed_careers_url=page_url)
        else:
            if args.no_discovery:
                html, err = _fetch_html(session, careers_url, timeout=timeout)
                if err:
                    fetch_errors += 1
                    print(
                        f"[job_watcher] FETCH ERROR company_id={cid} name={name!r} url={careers_url} — {err}",
                        file=sys.stderr,
                    )
                    continue
                listings = extract_job_listings(html, page_url=careers_url, seed_careers_url=careers_url)
            else:
                listings, sub_errs, disc_logs = crawl_careers_site(
                    session,
                    seed_url=careers_url,
                    timeout=timeout,
                    delay_seconds=delay,
                    max_pages=max_pages,
                    max_depth=max_depth,
                    min_discovery_score=min_disc,
                )
                fetch_errors += sub_errs
                if args.verbose_discovery:
                    for ln in disc_logs:
                        print(f"[job_watcher] {ln}", file=sys.stderr)
                else:
                    summary = next((ln for ln in reversed(disc_logs) if ln.startswith("discovery summary")), "")
                    if summary:
                        print(f"[job_watcher] company_id={cid} {summary}", file=sys.stderr)

        print(
            f"[job_watcher] company_id={cid} {name!r}: extracted "
            f"{len(listings)} plausible job postings (links + embedded JSON-LD)",
        )

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
        f"skipped_low_score={skipped_score} fetch_errors={fetch_errors}",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

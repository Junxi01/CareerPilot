"""Parse `scripts/examples/mock_careers_page.html` without HTTP."""

from pathlib import Path

from scripts.job_watcher import _careers_url_skip_reason, _host_blocked, extract_job_listings


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def test_extract_mock_careers_page_finds_three_roles() -> None:
    html = (_repo_root() / "scripts/examples/mock_careers_page.html").read_text(encoding="utf-8")
    seed = "https://careers.example.com/"
    page = "https://careers.example.com/jobs/"
    listings = extract_job_listings(html, page_url=page, seed_careers_url=seed)
    titles = {x.title.strip() for x in listings}
    assert "Senior Backend Engineer" in titles
    assert "Platform Reliability Engineer" in titles
    assert "Data Analyst" in titles
    assert len(listings) >= 3


def test_linkedin_careers_url_is_blocked_reason() -> None:
    reason = _careers_url_skip_reason("https://www.linkedin.com/jobs/search")
    assert reason is not None
    assert "linkedin" in reason.lower()


def test_host_blocked_matching() -> None:
    assert _host_blocked("www.indeed.com") is True
    assert _host_blocked("jobs.examplecorp.com") is False

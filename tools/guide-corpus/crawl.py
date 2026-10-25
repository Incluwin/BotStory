"""Polite crawler for player-written v83-era MapleStory guides.

Writes a local corpus that is never committed (forum posts belong to their authors):
  <out>/raw/<host>/<sha1>.html.gz   every fetched page, so re-runs and re-parses cost no requests
  <out>/threads/<source>/<id>.json  one parsed thread: title, starter, dates, posts (OP flagged)
  <out>/crawl-log.jsonl             one line per request

Sources:
  royals      royals.ms forum Guides + Class Guides (MapleRoyals, a v83 server)
  southperry  southperry.net guide, training and job forums, threads started before GMS Big Bang

Usage: py -3 -I crawl.py --out <dir> --source royals|southperry [--max-threads N] [--delay 3]
"""
import argparse
import datetime as dt
import gzip
import hashlib
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import urllib.robotparser
from html.parser import HTMLParser

UA = "BotStoryGuideIndexer/0.1 (non-commercial game-server knowledge base; ~1 req/3s)"

# GMS Big Bang (v.93) shipped 2010-07-14; threads started after it describe a different game.
SOUTHPERRY_CUTOFF = dt.date(2010, 7, 14)
SOUTHPERRY_FORUMS = {83: "MapleGuides", 32: "Training Center", 33: "Game Mechanics", 44: "Beginner",
                     46: "Warrior", 39: "Magician", 47: "Bowman", 43: "Thief"}
ROYALS_FORUMS = {"guides.57": "Guides", "class-guides.109": "Class Guides"}
# Later pages of long threads are mostly replies; the guide itself is the opening posts.
MAX_THREAD_PAGES = 3


class Fetcher:
    def __init__(self, out, delay):
        self.out, self.delay = out, delay
        self.last = {}
        self.robots = {}
        self.blocked = set()
        self.log = open(os.path.join(out, "crawl-log.jsonl"), "a", encoding="utf-8")

    def _cache_path(self, url):
        host = urllib.request.urlparse(url).netloc
        return os.path.join(self.out, "raw", host, hashlib.sha1(url.encode()).hexdigest() + ".html.gz")

    def _allowed(self, url):
        parts = urllib.request.urlparse(url)
        root = f"{parts.scheme}://{parts.netloc}"
        if root not in self.robots:
            rp = urllib.robotparser.RobotFileParser()
            try:
                body = self._get(root + "/robots.txt", allow_404=True)
                rp.parse((body or "").splitlines())
            except Exception:
                rp.parse([])
            self.robots[root] = rp
        return self.robots[root].can_fetch(UA, url)

    def _wait(self, host):
        gap = time.monotonic() - self.last.get(host, 0)
        if gap < self.delay:
            time.sleep(self.delay - gap)
        self.last[host] = time.monotonic()

    def _get(self, url, allow_404=False):
        host = urllib.request.urlparse(url).netloc
        for attempt in range(4):
            self._wait(host)
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Encoding": "identity"})
            try:
                with urllib.request.urlopen(req, timeout=40) as resp:
                    body = resp.read().decode(resp.headers.get_content_charset() or "utf-8", "replace")
                    self.log.write(json.dumps({"t": time.time(), "url": url, "status": resp.status}) + "\n")
                    return body
            except urllib.error.HTTPError as e:
                self.log.write(json.dumps({"t": time.time(), "url": url, "status": e.code}) + "\n")
                if e.code == 404 and allow_404:
                    return None
                if e.code in (429, 500, 502, 503, 504):
                    time.sleep(30 * (attempt + 1))
                    continue
                if e.code == 403:
                    self.blocked.add(host)
                raise
            except (urllib.error.URLError, TimeoutError):
                time.sleep(15 * (attempt + 1))
        raise RuntimeError(f"gave up on {url}")

    def page(self, url):
        path = self._cache_path(url)
        if os.path.exists(path):
            with gzip.open(path, "rt", encoding="utf-8") as f:
                return f.read()
        host = urllib.request.urlparse(url).netloc
        if host in self.blocked:
            raise RuntimeError(f"{host} answered 403 earlier; stopping requests to it")
        if not self._allowed(url):
            raise PermissionError(f"robots.txt disallows {url}")
        body = self._get(url)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with gzip.open(path, "wt", encoding="utf-8") as f:
            f.write(body)
        self.log.flush()
        return body


class TextOf(HTMLParser):
    """Post HTML -> plain text that keeps lines, list items and table rows."""
    BLOCK = {"p", "div", "br", "tr", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "table", "ul", "ol"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts, self.skip = [], 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"):
            self.skip += 1
        elif tag in self.BLOCK:
            self.parts.append("\n- " if tag == "li" else "\n")
        elif tag in ("td", "th"):
            self.parts.append(" | ")
        elif tag == "img":
            alt = dict(attrs).get("alt") or ""
            if alt and not alt.startswith("http") and len(alt) < 40:
                self.parts.append(f" {alt} ")

    def handle_endtag(self, tag):
        if tag in ("script", "style"):
            self.skip = max(0, self.skip - 1)
        elif tag in self.BLOCK:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self.skip:
            self.parts.append(data)


def to_text(fragment):
    p = TextOf()
    p.feed(fragment)
    text = "".join(p.parts)
    text = re.sub(r"\[/?(?:SIZE|COLOR|FONT|B|I|U|CENTER|LEFT|RIGHT|URL|IMG|INDENT)[^\]]*\]", "", text, flags=re.I)
    text = re.sub(r"[ \t ]+", " ", text)
    text = re.sub(r" *\n *", "\n", text)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def balanced(html_text, start, tag):
    """Inner HTML of the <tag> element whose opening tag begins at `start`."""
    open_end = html_text.index(">", start) + 1
    depth, pos = 1, open_end
    token = re.compile(rf"<(/?){tag}\b", re.I)
    while depth:
        m = token.search(html_text, pos)
        if not m:
            return html_text[open_end:]
        depth += -1 if m.group(1) else 1
        pos = m.end()
    return html_text[open_end:m.start()]


def segments(html_text, marker):
    starts = [m.start() for m in re.finditer(marker, html_text)]
    return [html_text[s:e] for s, e in zip(starts, starts[1:] + [len(html_text)])]


def txt(fragment):
    return html.unescape(re.sub(r"<[^>]+>", "", fragment)).strip()


# --- royals.ms (XenForo 1) -------------------------------------------------------------------------

ROYALS = "https://royals.ms/forum/"


def royals_date(seg):
    permalink = re.search(r'class="datePermalink"', seg)
    if permalink:
        seg = seg[permalink.start():]
    m = re.search(r'class="DateTime"[^>]*data-time="(\d+)"', seg)
    if m:
        return dt.datetime.fromtimestamp(int(m.group(1)), dt.timezone.utc).date().isoformat()
    m = re.search(r'class="DateTime"[^>]*title="([A-Z][a-z]{2} \d{1,2}, \d{4})', seg)
    if m:
        return dt.datetime.strptime(m.group(1), "%b %d, %Y").date().isoformat()
    return None


def royals_listing(fetch, forum):
    first = fetch.page(f"{ROYALS}forums/{forum}/")
    last = max([int(p) for p in re.findall(rf"forums/{re.escape(forum)}/page-(\d+)", first)] or [1])
    for n in range(1, last + 1):
        body = first if n == 1 else fetch.page(f"{ROYALS}forums/{forum}/page-{n}")
        for seg in segments(body, r'<li id="thread-\d+"'):
            tid = re.match(r'<li id="thread-(\d+)"', seg).group(1)
            link = re.search(r'<a href="(threads/[^"]+/)"[^>]*class="PreviewTooltip"[^>]*>(.*?)</a>', seg, re.S)
            if not link:
                continue
            starter = re.search(r'data-author="([^"]*)"', seg)
            yield {"id": tid, "url": ROYALS + link.group(1), "title": txt(link.group(2)),
                   "starter": html.unescape(starter.group(1)) if starter else None,
                   "started": royals_date(seg), "sticky": "sticky" in seg[:300]}


def royals_posts(body):
    posts = []
    for seg in segments(body, r'<li id="post-\d+"'):
        author = re.search(r'data-author="([^"]*)"', seg)
        q = re.search(r'<blockquote class="messageText', seg)
        if not q:
            continue
        posts.append({"id": re.match(r'<li id="post-(\d+)"', seg).group(1),
                      "author": html.unescape(author.group(1)) if author else None,
                      "date": royals_date(seg[q.start():]),
                      "text": to_text(balanced(seg, q.start(), "blockquote"))})
    return posts


def royals_thread(fetch, t):
    body = fetch.page(t["url"])
    pages = max([int(p) for p in re.findall(r'data-last="(\d+)"', body)] or [1])
    posts = royals_posts(body)
    for n in range(2, min(pages, MAX_THREAD_PAGES) + 1):
        posts += royals_posts(fetch.page(f"{t['url']}page-{n}"))
    return pages, posts


# --- southperry.net (MyBB) -------------------------------------------------------------------------

SP = "https://www.southperry.net/"


def sp_date(text):
    m = re.search(r"(\d{4}-\d{2}-\d{2})", text)
    return m.group(1) if m else None


def southperry_listing(fetch, fid):
    url = f"{SP}forumdisplay.php?fid={fid}&sortby=started&order=asc&datecut=9999"
    first = fetch.page(url)
    last = max([int(p) for p in re.findall(r"page=(\d+)", first)] or [1])
    for n in range(1, last + 1):
        body = first if n == 1 else fetch.page(f"{url}&page={n}")
        past_cutoff = False
        for row in re.findall(r'<tr class="inline_row">(.*?)</tr>', body, re.S):
            link = re.search(r'<a href="showthread\.php\?tid=(\d+)"[^>]*>(.*?)</a>', row, re.S)
            if not link:
                continue
            started = sp_date(row.replace(link.group(0), ""))
            sticky = "forumdisplay_sticky" in row
            if not sticky and started and dt.date.fromisoformat(started) >= SOUTHPERRY_CUTOFF:
                past_cutoff = True
                continue
            starter = re.search(r'uid=\d+">(.*?)</a>', row, re.S)
            yield {"id": link.group(1), "url": f"{SP}showthread.php?tid={link.group(1)}", "title": txt(link.group(2)),
                   "starter": txt(starter.group(1)) if starter else None, "started": started, "sticky": sticky}
        if past_cutoff:
            return


def southperry_posts(body):
    posts = []
    for seg in segments(body, r'<div class="post "[^>]*id="post_\d+"'):
        pid = re.search(r'id="post_(\d+)"', seg).group(1)
        author = re.search(r'uid=\d+">(.*?)</a>', seg, re.S)
        date = re.search(r'class="post_date">([^<]*)', seg)
        b = re.search(r'<div class="post_body[^"]*" id="pid_\d+"', seg)
        if not b:
            continue
        posts.append({"id": pid, "author": txt(author.group(1)) if author else None,
                      "date": sp_date(date.group(1)) if date else None,
                      "text": to_text(balanced(seg, b.start(), "div"))})
    return posts


def southperry_thread(fetch, t):
    body = fetch.page(t["url"])
    pages = max([int(p) for p in re.findall(rf"tid={t['id']}(?:&amp;|&)page=(\d+)", body)] or [1])
    posts = southperry_posts(body)
    for n in range(2, min(pages, MAX_THREAD_PAGES) + 1):
        posts += southperry_posts(fetch.page(f"{t['url']}&page={n}"))
    return pages, posts


SOURCES = {
    "royals": (ROYALS_FORUMS, royals_listing, royals_thread),
    "southperry": (SOUTHPERRY_FORUMS, southperry_listing, southperry_thread),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join("tmp", "guide-corpus"))
    ap.add_argument("--source", choices=sorted(SOURCES), required=True)
    ap.add_argument("--max-threads", type=int, default=0)
    ap.add_argument("--delay", type=float, default=3.0)
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    os.makedirs(args.out, exist_ok=True)
    fetch = Fetcher(args.out, args.delay)
    forums, listing, thread = SOURCES[args.source]
    tdir = os.path.join(args.out, "threads", args.source)
    os.makedirs(tdir, exist_ok=True)
    done = 0
    for forum, forum_name in forums.items():
        for t in listing(fetch, forum):
            path = os.path.join(tdir, f"{t['id']}.json")
            if os.path.exists(path):
                continue
            try:
                pages, posts = thread(fetch, t)
            except PermissionError as e:
                print("skip", e)
                continue
            except Exception as e:
                print("error", t["url"], e)
                if urllib.request.urlparse(t["url"]).netloc in fetch.blocked:
                    return 2
                continue
            for p in posts:
                p["is_op"] = p["author"] == t["starter"]
            if posts and posts[0]["date"]:
                t["started"] = posts[0]["date"]
            if args.source == "southperry" and t["started"] and dt.date.fromisoformat(t["started"]) >= SOUTHPERRY_CUTOFF:
                continue
            record = dict(t, source=args.source, forum=forum_name, pages=pages, posts=posts,
                          fetched=dt.date.today().isoformat())
            with open(path, "w", encoding="utf-8") as f:
                json.dump(record, f, ensure_ascii=False, indent=1)
            done += 1
            op_chars = sum(len(p["text"]) for p in posts if p["is_op"])
            print(f"{done:5d} {args.source} {forum_name}: {t['title'][:70]} ({t['started']}, {pages}p, op {op_chars} chars)")
            if args.max_threads and done >= args.max_threads:
                return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())

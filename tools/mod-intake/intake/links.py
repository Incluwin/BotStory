"""Find links in message text and say what they point at (GitHub objects, pastes, downloads)."""
import re
import urllib.parse
from dataclasses import dataclass

URL_RE = re.compile(r"https?://[^\s<>()\[\]{}\"'`|]+", re.IGNORECASE)
TRAILING = ".,;:!?*_~"

PASTE_HOSTS = {"pastebin.com", "hastebin.com", "paste.ee", "ghostbin.com", "pastie.io", "rentry.co", "dpaste.org"}
DOWNLOAD_HOSTS = {"mega.nz", "mediafire.com", "drive.google.com", "dropbox.com", "1drv.ms", "onedrive.live.com"}
GIT_HOSTS = {"gitlab.com", "bitbucket.org", "codeberg.org"}


@dataclass(frozen=True)
class Link:
    url: str
    kind: str
    host: str
    owner: str = None
    repo: str = None
    ref: str = None
    path: str = None


def find_urls(text):
    urls = []
    for match in URL_RE.finditer(text or ""):
        url = match.group(0).rstrip(TRAILING)
        if url not in urls:
            urls.append(url)
    return urls


def classify(url):
    parsed = urllib.parse.urlparse(url)
    host = (parsed.hostname or "").lower()
    if host.startswith("www."):
        host = host[4:]
    parts = [urllib.parse.unquote(p) for p in parsed.path.split("/") if p]

    if host == "github.com":
        return _github(url, host, parts)
    if host == "gist.github.com":
        return Link(url, "gist", host, owner=parts[0] if len(parts) > 1 else None, ref=parts[-1] if parts else None)
    if host == "raw.githubusercontent.com" and len(parts) >= 4:
        return Link(url, "github_raw", host, parts[0], _repo(parts[1]), parts[2], "/".join(parts[3:]))
    if host in ("cdn.discordapp.com", "media.discordapp.net"):
        return Link(url, "discord_attachment", host)
    if host in ("discord.com", "discordapp.com") and parts[:1] == ["channels"]:
        return Link(url, "discord_message", host, path="/".join(parts[1:]))
    if host in PASTE_HOSTS:
        return Link(url, "paste", host, ref=parts[-1] if parts else None)
    if host in GIT_HOSTS:
        return Link(url, "git_other", host, parts[0] if parts else None, _repo(parts[1]) if len(parts) > 1 else None)
    if host in DOWNLOAD_HOSTS:
        return Link(url, "download", host)
    return Link(url, "other", host)


def _repo(name):
    return name[:-4] if name.endswith(".git") else name


GITHUB_NOT_OWNERS = {"orgs", "topics", "settings", "marketplace", "sponsors", "features", "about", "search"}


def _github(url, host, parts):
    if len(parts) < 2 or parts[0] in GITHUB_NOT_OWNERS:
        return Link(url, "github_other", host, owner=parts[0] if parts else None)
    owner, repo, rest = parts[0], _repo(parts[1]), parts[2:]
    if not rest:
        return Link(url, "github_repo", host, owner, repo)
    head = rest[0]
    if head == "commit" and len(rest) > 1:
        return Link(url, "github_commit", host, owner, repo, rest[1])
    if head == "pull" and len(rest) > 1:
        ref = rest[3] if len(rest) > 3 and rest[2] == "commits" else None
        return Link(url, "github_pull", host, owner, repo, ref, path=rest[1])
    if head in ("blob", "tree") and len(rest) > 1:
        return Link(url, "github_" + head, host, owner, repo, rest[1], "/".join(rest[2:]) or None)
    if head == "compare" and len(rest) > 1:
        return Link(url, "github_compare", host, owner, repo, "/".join(rest[1:]))
    if head == "releases":
        if len(rest) > 2 and rest[1] in ("tag", "download"):
            return Link(url, "github_release", host, owner, repo, rest[2], "/".join(rest[3:]) or None)
        return Link(url, "github_release", host, owner, repo)
    if head == "issues" and len(rest) > 1:
        return Link(url, "github_issue", host, owner, repo, path=rest[1])
    return Link(url, "github_repo", host, owner, repo, path="/".join(rest))


def extract(text, embeds=()):
    """Links in a message: its text first, then embed URLs it does not already contain."""
    urls = find_urls(text)
    for embed in embeds or ():
        url = embed.get("url")
        if url and url not in urls:
            urls.append(url)
    return [classify(u) for u in urls]

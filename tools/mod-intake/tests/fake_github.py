"""In-memory GitHub REST API for the fork-mining tests: repos, forks (newest first by stars), branches, compares.

A compare is looked up by (fork, head sha) from what a test registered; anything else compares as identical. The
paging, auth and rate-limit answers follow the real API's shapes.
"""
import json
import sys
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from intake.api import Response  # noqa: E402

API = "https://api.github.com"
TOKEN = "test-gh-token"


def json_response(status, payload, headers=None):
    return Response(status, dict(headers or {}, **{"Content-Type": "application/json"}),
                    json.dumps(payload).encode("utf-8"))


def commit(sha, message, login="alice", date="2024-05-01T10:00:00Z", merge=False):
    return {"sha": sha, "commit": {"message": message, "author": {"name": login.title(), "date": date}},
            "author": {"login": login, "id": sum(map(ord, login))}, "parents": [{"sha": "p1"}, {"sha": "p2"}]
            if merge else [{"sha": "p1"}]}


def file_change(name, status="modified", patch="@@ -1 +1 @@\n-old\n+new", previous=None):
    out = {"filename": name, "status": status, "additions": 1, "deletions": 1}
    if patch is not None:
        out["patch"] = patch
    if previous:
        out["previous_filename"] = previous
    return out


class FakeGitHub:
    def __init__(self):
        self.repos = {}
        self.children = {}
        self.branches = {}
        self.compares = {}
        self.failing = set()          # (fork, sha) whose compare answers 404
        self.flaky = set()            # (fork, sha) whose compare answers 502
        self.token = TOKEN
        self.requests_left = None     # after this many requests: 403 with x-ratelimit-remaining 0
        self.secondary = {}           # path fragment -> number of 403s with Retry-After still to send
        self.log = []

    def add_repo(self, full, repo_id, parent=None, default="master", created="2024-01-01T00:00:00Z",
                 pushed="2024-06-01T00:00:00Z", description=None, stars=0, archived=False, branches=None):
        owner = full.split("/")[0]
        self.repos[full] = {"id": repo_id, "full_name": full, "name": full.split("/")[1],
                            "owner": {"login": owner, "id": 1000 + repo_id}, "default_branch": default,
                            "created_at": created, "pushed_at": pushed, "description": description,
                            "stargazers_count": stars, "archived": archived, "fork": parent is not None,
                            "html_url": f"https://github.com/{full}"}
        self.branches[full] = dict(branches or {default: f"{owner}-head"})
        if parent:
            self.children.setdefault(parent, []).append(full)

    def set_compare(self, fork, head, ahead, commits, files, behind=3, merge_base="mb0000000000"):
        self.compares[(fork, head)] = {"status": "diverged", "ahead_by": ahead, "behind_by": behind,
                                       "merge_base_commit": {"sha": merge_base}, "commits": commits, "files": files}

    def push(self, fork, branch, head, pushed):
        self.branches[fork][branch] = head
        self.repos[fork]["pushed_at"] = pushed

    # Transport

    def request(self, method, url, headers):
        parsed = urllib.parse.urlparse(url)
        query = dict(urllib.parse.parse_qsl(parsed.query))
        path = urllib.parse.unquote(parsed.path)
        self.log.append(path)
        if headers.get("Authorization") != "Bearer " + self.token:
            return json_response(401, {"message": "Bad credentials"})
        if self.requests_left is not None:
            if self.requests_left <= 0:
                return json_response(403, {"message": "API rate limit exceeded"},
                                     {"X-RateLimit-Remaining": "0", "X-RateLimit-Reset": "1767225600"})
            self.requests_left -= 1
        for fragment, left in self.secondary.items():
            if fragment in path and left > 0:
                self.secondary[fragment] = left - 1
                return json_response(403, {"message": "You have exceeded a secondary rate limit"}, {"Retry-After": "7"})
        parts = path.strip("/").split("/")
        if parts[0] != "repos" or len(parts) < 3:
            return json_response(404, {"message": "Not Found"})
        full = f"{parts[1]}/{parts[2]}"
        if full not in self.repos:
            return json_response(404, {"message": "Not Found"})
        rest = parts[3:]
        if not rest:
            return json_response(200, dict(self.repos[full], forks_count=len(self.children.get(full, []))))
        if rest == ["forks"]:
            forks = sorted((dict(self.repos[f], forks_count=len(self.children.get(f, [])))
                            for f in self.children.get(full, [])), key=lambda r: -r["stargazers_count"])
            return json_response(200, self._page(forks, query))
        if rest == ["branches"]:
            items = [{"name": n, "commit": {"sha": s}} for n, s in self.branches[full].items()]
            return json_response(200, self._page(items, query))
        if rest[0] == "compare":
            basehead = "/".join(rest[1:])
            base, _, head_branch = basehead.partition("...")
            if ":" not in base:
                return json_response(404, {"message": "Not Found"})
            head = self.branches[full].get(head_branch)
            if head is None or (full, head) in self.failing:
                return json_response(404, {"message": "Not Found"})
            if (full, head) in self.flaky:
                return json_response(502, {"message": "Server Error"})
            found = self.compares.get((full, head))
            if found is None:
                found = {"status": "identical", "ahead_by": 0, "behind_by": 0, "merge_base_commit": {"sha": head},
                         "commits": [], "files": []}
            return json_response(200, found)
        return json_response(404, {"message": "Not Found"})

    @staticmethod
    def _page(items, query):
        per_page, page = int(query.get("per_page", 30)), int(query.get("page", 1))
        return items[(page - 1) * per_page:page * per_page]

    def calls(self, fragment):
        return [p for p in self.log if fragment in p]

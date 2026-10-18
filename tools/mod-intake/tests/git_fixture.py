"""Local bare git repositories standing in for GitHub, and a fake web for pastes. No network anywhere: the fetch
under test may only use the file transport (GIT_ALLOW_PROTOCOL=file)."""
import os
import shutil
import subprocess
from pathlib import Path

from intake.api import Response

IDENTITY = ("-c", "user.name=Intake Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false",
            "-c", "tag.gpgsign=false", "-c", "init.defaultBranch=main", "-c", "core.autocrlf=false")


def have_git():
    return shutil.which("git") is not None


def isolated_env(folder):
    """The caller's environment with no system or user git config (no credential helper, no hooks path)."""
    empty = Path(folder) / "empty.gitconfig"
    empty.write_text("", "utf-8")
    env = dict(os.environ)
    env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=str(empty))
    return env


def git(*args, env=None):
    p = subprocess.run(["git", *IDENTITY, *args], env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                       stderr=subprocess.PIPE)
    if p.returncode:
        raise AssertionError(f"git {' '.join(args)} failed: {p.stderr.decode('utf-8', 'replace')}")
    return p.stdout.decode("utf-8", "replace").strip()


class FakeRemote:
    """A bare repo served over file:// with partial-clone and fetch-by-sha allowed (as GitHub allows them)."""

    def __init__(self, root, name, env):
        self.env = env
        self.bare = Path(root) / f"{name}.git"
        self.work = Path(root) / f"{name}-work"
        git("init", "--quiet", "--bare", str(self.bare), env=env)
        git("-C", str(self.bare), "config", "uploadpack.allowFilter", "true", env=env)
        git("-C", str(self.bare), "config", "uploadpack.allowAnySHA1InWant", "true", env=env)
        git("init", "--quiet", str(self.work), env=env)

    @property
    def uri(self):
        return self.bare.as_uri()

    def run(self, *args):
        return git("-C", str(self.work), *args, env=self.env)

    def commit(self, files, message):
        for name, text in files.items():
            path = self.work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, "utf-8")
        self.run("add", "-A")
        self.run("commit", "--quiet", "-m", message)
        return self.run("rev-parse", "HEAD")

    def push(self, *refspecs):
        self.run("push", "--quiet", str(self.bare), *refspecs)


def build_bosshp(root, env):
    """hookwright/v83-bosshp:

        c0 -- c1 (tag v1.0, annotated) -- c2  main
               |                         \\-- c3  feature/x
               \\-- c4  refs/pull/7/head  \\-- c5  on no ref (fetchable only by its sha)
    """
    r = FakeRemote(root, "bosshp", env)
    c = {}
    c["c0"] = r.commit({"README.md": "Boss HP bar\n", "src/BossHp.cpp": "int hp = 1;\n", "docs/notes.txt": "no link\n"},
                       "c0")
    c["c1"] = r.commit({"src/BossHp.cpp": "int hp = 2;\n"}, "c1")
    r.run("tag", "-a", "v1.0", "-m", "release 1.0")
    c["c2"] = r.commit({"src/Extra.cpp": "void extra() {}\n"}, "c2")
    r.run("checkout", "--quiet", "-b", "feature/x")
    c["c3"] = r.commit({"src/BossHp.cpp": "int hp = 3;\n"}, "c3")
    r.run("checkout", "--quiet", "-b", "pr7", c["c1"])
    c["c4"] = r.commit({"src/Fix.cpp": "void fix() {}\n"}, "c4")
    r.run("checkout", "--quiet", "-b", "scratch", c["c2"])
    c["c5"] = r.commit({"src/Dangling.cpp": "int dangling;\n"}, "c5")
    r.run("checkout", "--quiet", "main")
    r.push("main", "feature/x", "refs/tags/v1.0", f"{c['c4']}:refs/pull/7/head", f"{c['c5']}:refs/heads/scratch")
    r.push(":refs/heads/scratch")
    return r, c


def build_gist(root, env):
    g = FakeRemote(root, "gist", env)
    sha = g.commit({"fix.cpp": "if (!stage) return;\n"}, "gist")
    g.push("main")
    return g, sha


class FakeWeb:
    """GET routes: url -> Response, or a list of Responses served in turn (the last one repeats)."""

    def __init__(self, routes=None):
        self.routes = dict(routes or {})
        self.requests = []

    def request(self, method, url, headers):
        self.requests.append(url)
        route = self.routes.get(url)
        if isinstance(route, list):
            route = route.pop(0) if len(route) > 1 else route[0]
        return route or Response(404, {"Content-Type": "text/plain"}, b"not found")


def text(body, status=200, content_type="text/plain; charset=utf-8", **headers):
    return Response(status, {"Content-Type": content_type, **headers}, body.encode("utf-8"))

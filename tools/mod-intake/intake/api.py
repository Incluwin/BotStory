"""Discord REST client (API v10): bot auth, rate limits, paging. Standard library only."""
import http.client
import json
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://discord.com/api/v10"
USER_AGENT = "DiscordBot (https://github.com/Incluwin/BotStory, 1.0) botstory-mod-intake"
MAX_ATTEMPTS = 6


class DiscordError(Exception):
    def __init__(self, status, path, message):
        super().__init__(f"HTTP {status} on {path}: {message}")
        self.status = status
        self.path = path


class Unauthorized(DiscordError):
    pass


class Forbidden(DiscordError):
    pass


class NotFound(DiscordError):
    pass


class Response:
    def __init__(self, status, headers, body):
        self.status = status
        self.headers = {k.lower(): v for k, v in headers.items()}
        self.body = body

    def json(self):
        return json.loads(self.body.decode("utf-8"))


class UrllibTransport:
    """The real network. Tests replace it with tests/fake_discord.py."""

    def __init__(self, timeout=60):
        self.timeout = timeout

    def request(self, method, url, headers):
        req = urllib.request.Request(url, method=method, headers=headers)
        try:
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    return Response(r.status, dict(r.headers.items()), r.read())
            except urllib.error.HTTPError as e:
                return Response(e.code, dict(e.headers.items()), e.read())
        except http.client.HTTPException as e:  # IncompleteRead...: the connection dropped mid-body; callers retry
            raise ConnectionError(f"connection dropped: {e!r}"[:300]) from e


class DiscordClient:
    def __init__(self, token, transport=None, sleep=time.sleep, api=API):
        self.token = token
        self.transport = transport or UrllibTransport()
        self.sleep = sleep
        self.api = api
        self.requests = 0

    def get(self, path, **params):
        url = self.api + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        headers = {"Authorization": "Bot " + self.token, "User-Agent": USER_AGENT}
        for attempt in range(MAX_ATTEMPTS):
            try:
                resp = self.transport.request("GET", url, headers)
            except OSError as e:  # URLError, timeouts, resets
                self.requests += 1
                if attempt == MAX_ATTEMPTS - 1:
                    raise DiscordError(0, path, f"network error: {e}") from e
                self.sleep(2 ** attempt)
                continue
            self.requests += 1
            if resp.status == 429:
                self.sleep(_retry_after(resp))
                continue
            if resp.status >= 500 and attempt < MAX_ATTEMPTS - 1:
                self.sleep(2 ** attempt)
                continue
            if resp.headers.get("x-ratelimit-remaining") == "0":
                self.sleep(float(resp.headers.get("x-ratelimit-reset-after", "1")))
            if resp.status < 400:
                return resp.json()
            raise _error(resp, path)
        raise DiscordError(429, path, "still rate limited after retries")

    def download(self, url):
        """GET a CDN file. No Authorization header: the token only goes to the API."""
        return self.transport.request("GET", url, {"User-Agent": USER_AGENT})

    # Endpoints

    def guild_channels(self, guild_id):
        return self.get(f"/guilds/{guild_id}/channels")

    def active_threads(self, guild_id):
        return self.get(f"/guilds/{guild_id}/threads/active")["threads"]

    def archived_public_threads(self, channel_id, page_size=100):
        before = None
        while True:
            params = {"limit": page_size}
            if before:
                params["before"] = before
            page = self.get(f"/channels/{channel_id}/threads/archived/public", **params)
            threads = page["threads"]
            yield from threads
            if not page.get("has_more") or not threads:
                return
            before = threads[-1]["thread_metadata"]["archive_timestamp"]

    def messages_after(self, channel_id, after, page_size=100):
        """Pages of messages newer than `after`, each page oldest first."""
        while True:
            page = self.get(f"/channels/{channel_id}/messages", after=after, limit=page_size)
            page.sort(key=lambda m: int(m["id"]))
            if page:
                yield page
            if len(page) < page_size:
                return
            after = page[-1]["id"]

    def message(self, channel_id, message_id):
        return self.get(f"/channels/{channel_id}/messages/{message_id}")


def _retry_after(resp):
    try:
        return float(resp.json()["retry_after"])
    except (ValueError, KeyError, TypeError):
        return float(resp.headers.get("retry-after", "1"))


def _error(resp, path):
    try:
        message = resp.json().get("message", "")
    except ValueError:
        message = resp.body[:200].decode("utf-8", "replace")
    cls = {401: Unauthorized, 403: Forbidden, 404: NotFound}.get(resp.status, DiscordError)
    return cls(resp.status, path, message)

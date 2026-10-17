"""Settings from <data dir>/.env (environment variables win). The data dir must not be inside a git repo."""
import os
from dataclasses import dataclass
from pathlib import Path

from .sync import IntakeError

# tools/mod-intake/intake/config.py -> the folder holding the server repo (FriendServer in the main checkout)
DEFAULT_DATA = Path(__file__).resolve().parents[4] / "mod-intake-data"
KEYS = ("DISCORD_BOT_TOKEN", "DISCORD_GUILD_ID", "INTAKE_CHANNELS", "INTAKE_MAX_ATTACHMENT_MB")


@dataclass
class Config:
    data_dir: Path
    token: str
    guild_id: str
    channel_ids: tuple
    max_attachment_bytes: int

    @property
    def db_path(self):
        return self.data_dir / "intake.sqlite3"

    @property
    def attachments_dir(self):
        return self.data_dir / "attachments"

    def require_discord(self):
        missing = [k for k, v in (("DISCORD_BOT_TOKEN", self.token), ("DISCORD_GUILD_ID", self.guild_id)) if not v]
        if missing:
            raise IntakeError(f"{' and '.join(missing)} missing: put them in {self.data_dir / '.env'} "
                              f"(see tools/mod-intake/README.md, 'Bot setup')")


def load(data_dir=None, environ=None):
    environ = os.environ if environ is None else environ
    data_dir = Path(data_dir or environ.get("MOD_INTAKE_DATA") or DEFAULT_DATA).resolve()
    repo = enclosing_repo(data_dir)
    if repo:
        raise IntakeError(f"data dir {data_dir} is inside the git repo {repo}: Discord messages must never be "
                          f"committed. Use a folder outside any repo (default: {DEFAULT_DATA}).")
    values = read_env_file(data_dir / ".env")
    values.update({k: environ[k] for k in KEYS if environ.get(k)})
    channels = tuple(int(x) for x in values.get("INTAKE_CHANNELS", "").replace(" ", "").split(",") if x)
    max_mb = float(values.get("INTAKE_MAX_ATTACHMENT_MB") or 100)
    return Config(data_dir, values.get("DISCORD_BOT_TOKEN", ""), values.get("DISCORD_GUILD_ID", ""), channels,
                  int(max_mb * 1024 * 1024))


def enclosing_repo(path):
    for folder in (path, *path.parents):
        if (folder / ".git").exists():
            return folder
    return None


def read_env_file(path):
    values = {}
    if not path.exists():
        return values
    for line in path.read_text("utf-8-sig").splitlines():  # utf-8-sig: PowerShell writes a BOM
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values

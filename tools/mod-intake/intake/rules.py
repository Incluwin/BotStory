"""P3 rule tags: what plain matching can tell about a release, with no model involved.

Evidence (facts, also sent to Jev): v83-range addresses, file paths and their layouts, Java packages, telltale
identifiers, version and server-family words, file types of attachments. Hints (conclusions, never sent to Jev):
the kinds, versions and families that evidence points at, used when no judgment exists and shown on the board next
to the judgments.
"""
import os
import re

from . import codeblocks as codelib

# v83 MapleStory.exe maps at 0x00400000; its code and data addresses are 0x004xxxxx-0x00Cxxxxx
ADDRESS_RE = re.compile(r"(?<![0-9A-Za-z_])0x(?:00)?([4-9A-Ca-c][0-9A-Fa-f]{5})(?![0-9A-Za-z_])")
TITLE_VERSION_RE = re.compile(r"(?i)(?<![A-Za-z0-9])v\.?\s?(\d{2,3})(?:\.\d+)*(?![0-9])")
REGION_VERSION_RE = re.compile(r"(?i)(?<![A-Za-z0-9])(?:gms|msea|kms|jms|tms|ems|bms|cms)\s*v?\.?\s*(\d{2,3})(?![0-9])")
CODE_EXT = ("java|js|sql|xml|cpp|cc|c|h|hpp|cs|py|lua|wz|img|json|ya?ml|ini|properties|txt|md|patch|diff|bat|sh|ps1"
            "|kt|gradle|vcxproj|sln|cmake|asm|dll|exe")
PATH_RE = re.compile(r"(?<![\w/.-])((?:[\w.-]+/)+[\w.-]+\.(?:%s))(?![\w])" % CODE_EXT, re.IGNORECASE)
FILE_RE = re.compile(r"(?<![\w/.-])([\w-]+(?:\.[\w-]+)*\.(?:java|js|sql|cpp|hpp|h|cs|wz|img|dll|exe|patch|diff))"
                     r"(?![\w])", re.IGNORECASE)
URL_RE = re.compile(r"https?://\S+", re.IGNORECASE)
PACKAGE_RE = re.compile(r"(?m)^\s*package\s+([a-z_][\w]*(?:\.[\w]+)+)\s*;")
IMPORT_RE = re.compile(r"(?m)^[+-]?\s*import\s+(?:static\s+)?([a-z_]\w*(?:\.[a-z_]\w*)*)(?:\.[A-Z]\w*)*(?:\.\*)?\s*;")
MAVEN_JAVA_RE = re.compile(r"(?:^|/)src/main/java/((?:[a-z_]\w*/)*[a-z_]\w*)/[A-Z]\w*\.java$")
FLAT_JAVA_RE = re.compile(r"(?:^|/)src/((?:net|client|server|tools|scripting|constants|provider|database|config)"
                          r"(?:/[a-z_]\w*)*)/[A-Z]\w*\.java$")

# Names that tell server sources apart (P0nk's Cosmic dropped the Maple prefix) and client hook code apart
IDENTIFIERS = (
    ("MaplePacketCreator", re.compile(r"\bMaplePacketCreator\b")),
    ("MapleCharacter", re.compile(r"\bMapleCharacter\b")),
    ("MapleClient", re.compile(r"\bMapleClient\b")),
    ("PacketCreator", re.compile(r"(?<![A-Za-z])PacketCreator\b")),
    ("client.Character", re.compile(r"\bclient\.Character\b")),
    ("YamlConfig", re.compile(r"\bYamlConfig\b")),
    ("ServerConstants", re.compile(r"\bServerConstants\b")),
    ("CWvsContext", re.compile(r"\bCWvsContext\b")),
    ("CUserLocal", re.compile(r"\bCUserLocal\b")),
    ("CField", re.compile(r"\bCField\b")),
    ("CWnd", re.compile(r"\bCWnd\b")),
    ("ZXString", re.compile(r"\bZXString\b")),
    ("IWzProperty", re.compile(r"\bIWz(?:Property|Canvas)\w*\b")),
    ("MEMBER_HOOK", re.compile(r"\bMEMBER_HOOK\b")),
    ("DetourAttach", re.compile(r"\bDetour(?:Attach|TransactionBegin)\b")),
)
CLIENT_IDENTIFIERS = {"CWvsContext", "CUserLocal", "CField", "CWnd", "ZXString", "IWzProperty", "MEMBER_HOOK",
                      "DetourAttach"}
HEAVENMS_IDENTIFIERS = {"MaplePacketCreator", "MapleCharacter", "MapleClient"}
COSMIC_IDENTIFIERS = {"PacketCreator", "client.Character", "YamlConfig"}

# Words naming a server source or client base; v83 ones say the version too
FAMILY_WORDS = (
    ("Cosmic", r"cosmic", "cosmic", "v83"),
    ("HeavenMS", r"heaven\s?ms", "heavenms_line", "v83"),
    ("Solaxia", r"(?:maple)?solaxia", "heavenms_line", "v83"),
    ("MoopleDEV", r"moople\s?dev", "heavenms_line", "v83"),
    ("OdinMS", r"odin\s?ms", "heavenms_line", None),
    ("kaentake", r"kaentake", None, "v83"),
    ("Swordie", r"swordie", "other_base", "other"),
    ("Kinoko", r"kinoko", "other_base", "other"),
    ("Lithium", r"lithium", "other_base", "other"),
    ("AzureMS", r"azure\s?ms", "other_base", "other"),
)
FAMILY_RES = tuple((name, re.compile(r"(?i)(?<![A-Za-z])" + pattern + r"(?![A-Za-z])"), family, version)
                   for name, pattern, family, version in FAMILY_WORDS)

LAYOUTS = (
    ("maven_java", re.compile(r"(?:^|/)src/main/java/")),
    ("flat_src_java", re.compile(r"(?:^|/)src/(?:net|client|server|tools|scripting|constants)/[\w/]*\.java$")),
    ("scripts", re.compile(r"(?:^|/)scripts/(?:npc|quest|event|portal|reactor|map|item)/")),
    ("wz_xml", re.compile(r"\.img\.xml$|(?:^|/)wz/[^/]+\.wz/")),
    ("sql", re.compile(r"\.sql$|(?:^|/)(?:sql|database)/")),
    ("cpp", re.compile(r"\.(?:cpp|cc|c|h|hpp)$|(?:^|/)CMakeLists\.txt$|\.vcxproj$")),
    ("maven_pom", re.compile(r"(?:^|/)pom\.xml$")),
    ("gradle", re.compile(r"(?:^|/)build\.gradle(?:\.kts)?$")),
)

TEXT_EXT_LANG = {
    ".java": "java", ".js": "javascript", ".sql": "sql", ".xml": "xml", ".cpp": "cpp", ".cc": "cpp", ".c": "cpp",
    ".h": "cpp", ".hpp": "cpp", ".patch": "diff", ".diff": "diff", ".py": "python", ".cs": "csharp", ".lua": "lua",
    ".json": "json", ".yaml": "yaml", ".yml": "yaml", ".ini": "ini", ".properties": "ini", ".txt": "text",
    ".md": "text", ".asm": "asm", ".bat": "batch", ".sh": "shell", ".ps1": "powershell", ".kt": "kotlin",
}
BINARY_EXT = {".dll": "pe", ".exe": "pe", ".wz": "wz", ".img": "wz_img", ".zip": "zip", ".rar": "rar", ".7z": "7z",
              ".png": "image", ".jpg": "image", ".jpeg": "image", ".gif": "image", ".webp": "image", ".bmp": "image",
              ".mp4": "video", ".webm": "video", ".mov": "video"}
MAGIC = ((b"MZ", "pe"), (b"PKG1", "wz"), (b"PK\x03\x04", "zip"), (b"Rar!", "rar"), (b"7z\xbc\xaf\x27\x1c", "7z"),
         (b"\x89PNG", "image"), (b"GIF8", "image"), (b"\xff\xd8\xff", "image"), (b"BM", "image"))
TOOL_EXT = {".py", ".cs", ".csproj", ".sln"}


def addresses(text):
    found = {"0x00" + m.group(1).upper() for m in ADDRESS_RE.finditer(text or "")}
    found.discard("0x00400000")  # the image base: every module and every client mod names it
    return sorted(found)


def title_versions(title):
    found = {"v" + m.group(1) for m in TITLE_VERSION_RE.finditer(title or "")}
    found |= {"v" + m.group(1) for m in REGION_VERSION_RE.finditer(title or "")}
    return sorted(found, key=lambda v: int(v[1:]))


def family_words(text):
    return sorted({name for name, rx, _f, _v in FAMILY_RES if rx.search(text or "")})


def paths(text):
    """File paths written in message text (URLs taken out first: link paths come from the links table)."""
    text = URL_RE.sub(" ", text or "")
    found = []
    for rx in (PATH_RE, FILE_RE):
        for m in rx.finditer(text):
            value = m.group(1)
            if value[:2] in ("a/", "b/") and "/" in value[2:]:
                value = value[2:]  # a diff's --- a/... +++ b/... headers
            if value not in found and not any(value != f and f.endswith("/" + value) for f in found):
                found.append(value)
    return found


def packages(text):
    found = set(m.group(1) for m in PACKAGE_RE.finditer(text or ""))
    for m in IMPORT_RE.finditer(text or ""):
        name = m.group(1)
        if not name.startswith(("java.", "javax.", "jdk.", "sun.", "org.slf4j", "com.google.", "org.apache.")):
            found.add(name)
    return sorted(found)


def path_packages(file_paths):
    found = set()
    for p in file_paths:
        p = p.replace("\\", "/")
        for rx in (MAVEN_JAVA_RE, FLAT_JAVA_RE):
            m = rx.search(p)
            if m:
                found.add(m.group(1).replace("/", "."))
                break
    return sorted(found)


def identifiers(text):
    return [name for name, rx in IDENTIFIERS if rx.search(text or "")]


def layouts(file_paths):
    found = []
    for name, rx in LAYOUTS:
        if any(rx.search(p.replace("\\", "/")) for p in file_paths):
            found.append(name)
    return found


def extension(name):
    lower = (name or "").lower()
    if lower.endswith(".img.xml"):
        return ".img.xml"
    return os.path.splitext(lower)[1]


def ext_counts(file_paths):
    counts = {}
    for p in file_paths:
        ext = extension(p) or "(none)"
        counts[ext] = counts.get(ext, 0) + 1
    return dict(sorted(counts.items(), key=lambda kv: (-kv[1], kv[0])))


def file_type(name, head=None):
    """What an attachment is, from its first bytes when we have them, else from its name."""
    ext = extension(name)
    if head:
        for magic, kind in MAGIC:
            if head.startswith(magic):
                return kind
        if len(head) >= 12 and head[4:8] == b"ftyp":
            return "video"
        if _looks_text(head):
            return "text:" + TEXT_EXT_LANG.get(ext, "text")
        return BINARY_EXT.get(ext, "binary")
    if ext in TEXT_EXT_LANG:
        return "text:" + TEXT_EXT_LANG[ext]
    return BINARY_EXT.get(ext, "unknown")


def _looks_text(head):
    if head.startswith((b"\xef\xbb\xbf", b"\xff\xfe", b"\xfe\xff")):
        return True
    if b"\0" in head:
        return False
    try:
        head.decode("utf-8")
        return True
    except UnicodeDecodeError as e:
        return e.start >= len(head) - 3  # a multi-byte character cut by the read size


def decode_text(body):
    for bom, codec in ((b"\xef\xbb\xbf", "utf-8"), (b"\xff\xfe", "utf-16-le"), (b"\xfe\xff", "utf-16-be")):
        if body.startswith(bom):
            return body[len(bom):].decode(codec, "replace")
    return body.decode("utf-8", "replace")


class Evidence:
    """Facts gathered over one scope of a thread (the release post and what it links, or the replies)."""

    def __init__(self):
        self.addresses = set()
        self.paths = []
        self.packages = set()
        self.identifiers = set()
        self.family_words = set()
        self.versions = set()
        self.code_languages = set()
        self.file_types = set()
        self.changed_files = []   # paths in pinned commits, PRs, compares, linked files and trees
        self._changed = set()
        self.tree_files = 0       # files in whole-repository snapshots (counted, listed in the dossier)

    def add_text(self, text):
        self.addresses.update(addresses(text))
        for p in paths(text):
            if p not in self.paths:
                self.paths.append(p)
        self.packages.update(packages(text))
        self.identifiers.update(identifiers(text))
        self.family_words.update(family_words(text))
        self.versions.update(title_versions(text))

    def add_code(self, language, text):
        self.code_languages.add(language)
        self.add_text(text)

    def add_files(self, file_paths, whole_tree=False):
        if whole_tree:
            self.tree_files += len(file_paths)
        for p in file_paths:  # a whole server fork lists ~10k files
            if p not in self._changed:
                self._changed.add(p)
                self.changed_files.append(p)
        self.packages.update(path_packages(file_paths))

    def to_dict(self, our_addresses=None):
        all_paths = self.paths + self.changed_files
        shared = []
        for address in sorted(self.addresses):
            modules = (our_addresses or {}).get(address)
            if modules:
                shared.append({"address": address, "our_modules": sorted(modules)})
        return {
            "addresses": sorted(self.addresses),
            "addresses_our_client_also_uses": shared,
            "file_paths_in_text": self.paths[:60],
            "path_layouts": layouts(all_paths),
            "file_extensions": ext_counts(all_paths),
            "java_packages": sorted(self.packages | set(path_packages(self.paths)))[:40],
            "identifiers": sorted(self.identifiers),
            "family_words": sorted(self.family_words),
            "version_words": sorted(self.versions, key=lambda v: int(v[1:])),
            "code_languages": sorted(self.code_languages),
            "attachment_types": sorted(self.file_types),
        }


def hints(title, release, replies=None):
    """Rule conclusions from a dossier's evidence dicts: kinds (ordered by strength), versions, families."""
    ev = release
    exts = set(ev["file_extensions"])
    types = set(ev["attachment_types"])
    idents = set(ev["identifiers"])
    words = set(ev["family_words"]) | set(family_words(title))
    langs = set(ev["code_languages"])
    layouts_ = set(ev["path_layouts"])
    why = []

    kinds = []
    if ev["addresses"] or idents & CLIENT_IDENTIFIERS or "pe" in types or "cpp" in layouts_ or "cpp" in langs \
            or "asm" in langs or "kaentake" in words:
        kinds.append("client_hook")
        why.append("client hook: " + ", ".join(_reasons(
            (ev["addresses"], f"{len(ev['addresses'])} v83-range address(es)"),
            (idents & CLIENT_IDENTIFIERS, "client class names"), ("pe" in types, "DLL/EXE attached"),
            ("cpp" in layouts_ or "cpp" in langs, "C++ code"), ("asm" in langs, "assembly"),
            ("kaentake" in words, "mentions kaentake"))))
    if types & {"wz", "wz_img"} or exts & {".wz", ".img", ".img.xml"} or "wz_xml" in layouts_:
        kinds.append("client_wz")
        why.append("client WZ: WZ files or WZ XML")
    if ev["java_packages"] or ".java" in exts or "java" in langs or idents & (HEAVENMS_IDENTIFIERS
                                                                                 | COSMIC_IDENTIFIERS):
        kinds.append("server_java")
        why.append("server Java: " + ", ".join(_reasons(
            (ev["java_packages"], "Java packages"), (".java" in exts, ".java files"), ("java" in langs, "Java code"),
            (idents & (HEAVENMS_IDENTIFIERS | COSMIC_IDENTIFIERS), "server class names"))))
    if "scripts" in layouts_ or ("javascript" in langs) or "text:javascript" in types:
        kinds.append("server_script")
        why.append("server script: scripts/ paths or script code")
    if "sql" in layouts_ or "sql" in langs or "text:sql" in types:
        kinds.append("database")
        why.append("database: SQL")
    if not kinds and (exts & TOOL_EXT or "python" in langs or "csharp" in langs):
        kinds.append("tool")
        why.append("tool: Python/C# sources and nothing for the client or server")

    versions = []
    for v in title_versions(title):
        versions.append(("v83" if v == "v83" else "other", f"title says {v}"))
    for name, _rx, _family, version in FAMILY_RES:
        if name in words and version:
            versions.append((version, f"names {name}"))
    if ev["addresses_our_client_also_uses"]:
        versions.append(("v83", "uses addresses our v83 client hooks"))
    if not title_versions(title):
        for v in ev["version_words"]:
            versions.append(("v83" if v == "v83" else "other", f"post says {v}"))

    families = []
    for name, _rx, family, _version in FAMILY_RES:
        if name in words and family:
            families.append((family, f"names {name}"))
    if idents & HEAVENMS_IDENTIFIERS:
        families.append(("heavenms_line", "uses " + ", ".join(sorted(idents & HEAVENMS_IDENTIFIERS))))
    if idents & COSMIC_IDENTIFIERS and not idents & HEAVENMS_IDENTIFIERS:
        families.append(("cosmic", "uses " + ", ".join(sorted(idents & COSMIC_IDENTIFIERS))))
    if "flat_src_java" in layouts_:
        families.append(("heavenms_line", "src/net, src/client layout"))
    if "maven_java" in layouts_:
        families.append(("cosmic", "src/main/java layout"))
    return {"kinds": kinds, "versions": versions, "families": families, "why": why}


def _reasons(*pairs):
    return [text for cond, text in pairs if cond]


def text_language(name, text):
    """The language of a text file: from its name, else guessed from its contents like a code block."""
    lang = TEXT_EXT_LANG.get(extension(name))
    return lang if lang and lang != "text" else codelib.guess(text)

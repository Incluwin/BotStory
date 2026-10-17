"""Fenced code blocks in Discord messages: the language from the fence, else a simple guess."""
import re
from dataclasses import dataclass

FENCE_RE = re.compile(r"```(.*?)```", re.DOTALL)
# Discord takes the fence's first line as the language when it is one word followed by a newline
LANG_LINE_RE = re.compile(r"([A-Za-z][A-Za-z0-9_+#-]{0,19})[ \t]*\r?\n")

ALIASES = {
    "c++": "cpp", "cc": "cpp", "cxx": "cpp", "hpp": "cpp", "h": "cpp", "js": "javascript", "ts": "typescript",
    "py": "python", "sh": "shell", "bash": "shell", "bat": "batch", "cmd": "batch", "ps1": "powershell",
    "yml": "yaml", "patch": "diff", "x86asm": "asm", "nasm": "asm", "masm": "asm", "kt": "kotlin", "cs": "csharp",
    "c#": "csharp", "md": "markdown", "txt": "text", "plaintext": "text", "mysql": "sql", "properties": "ini",
}

# First match wins. Cosmic NPC/quest/event scripts are JavaScript (cm., qm., em.), the client side is C++ with
# Win32 types, and patches are diffs; anything else is "text".
GUESSES = tuple((language, re.compile(pattern)) for language, pattern in (
    ("diff", r"(?m)^(?:\+\+\+ |--- a/|@@ -\d+(?:,\d+)? \+\d+)"),
    ("xml", r"\A\s*<(?:\?xml|imgdir\b|!--|[A-Za-z][\w:.-]*(?:\s[^>]*)?/?>)"),
    ("sql", r"(?im)^\s*(?:select\s.+\sfrom\s|insert\s+into\s|update\s+`?\w+`?\s+set\s|delete\s+from\s"
            r"|create\s+table\s|alter\s+table\s|drop\s+table\s)"),
    ("javascript", r"\b(?:cm|qm|em|pi|eim|rm|ms)\.\w+\(|\bfunction\s+(?:start|action|end|enter|init|setup|act)\s*\("),
    ("java", r"(?m)^\s*(?:package\s+[\w.]+;|import\s+(?:static\s+)?[\w.]+(?:\.\*)?;)|@Override\b"
             r"|\b(?:public|private|protected)\s+(?:(?:static|final|synchronized)\s+)*[\w<>\[\],? ]+\s+\w+\s*\("
             r"|\bSystem\.out\.|\.(?:getPlayer|getClient|sendPacket)\(\)"),
    ("cpp", r"(?m)^\s*#\s*(?:include|define|pragma|ifdef|ifndef)\b|\bnullptr\b"
            r"|\b(?:__stdcall|__fastcall|__thiscall|__cdecl|DWORD|BYTE|BOOL|HMODULE|LPVOID)\b|\w::\w|\w->\w"),
    ("asm", r"(?im)^\s*(?:[0-9a-f]{6,8}[:\s]+)?(?:mov|movzx|lea|push|pop|call|jmp|je|jne|jz|jnz|cmp|test|xor|add|sub"
            r"|and|or)\s+(?:e?[abcd]x\b|e?[sd]i\b|e?[bs]p\b|[abcd][lh]\b|dword|word|byte|qword|\[|0x)"),
    ("python", r"(?m)^\s*(?:def\s+\w+\s*\(.*\)\s*:|class\s+\w+(?:\(.*\))?\s*:|from\s+[\w.]+\s+import\s"
               r"|import\s+\w+\s*$)"),
    ("json", r"\A\s*(?:\{\s*\"|\[\s*[\{\[\"\d])"),
    ("ini", r"(?m)\A\s*\[[\w .-]+\][ \t]*$"),
    ("javascript", r"\bfunction\s+\w+\s*\(|\b(?:var|let|const)\s+\w+\s*=|=>"),
))


@dataclass(frozen=True)
class Block:
    index: int
    language: str
    guessed: bool
    text: str


def extract(content):
    """Closed ``` fences in order; empty ones are left out. An unclosed fence is plain text, as Discord shows it."""
    blocks = []
    for match in FENCE_RE.finditer(content or ""):
        inner = match.group(1)
        language = None
        head = LANG_LINE_RE.match(inner)
        if head:
            language = head.group(1).lower()
            language = ALIASES.get(language, language)
            inner = inner[head.end():]
        text = inner.strip("\r\n")
        if not text.strip():
            continue
        blocks.append(Block(len(blocks), language or guess(text), language is None, text))
    return blocks


def guess(text):
    for language, pattern in GUESSES:
        if pattern.search(text):
            return language
    return "text"

"""The small part of YAML the catalog files use, standard library only.

dump() writes block mappings and sequences, flow lists of short scalars, plain scalars where they read back the same,
and double-quoted strings otherwise. load() reads that back, plus what a person editing the file by hand tends to
write: comments, single quotes, flow lists and maps of scalars, empty values, and | or > block text. Anchors, tags,
multi-document streams and multi-line flow collections are not supported and raise YamlError.
"""
import json
import re

INDENT = 2
FLOW_WIDTH = 100
RESERVED = {"null", "~", "true", "false", "yes", "no", "on", "off", "y", "n"}
PLAIN_RE = re.compile(r"^[A-Za-z_./][A-Za-z0-9_ ./()@+-]*$")
NUMBER_RE = re.compile(r"^[-+]?(?:\d[\d_]*(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?$")
INT_RE = re.compile(r"^[-+]?\d+$")
HEX_RE = re.compile(r"^0x[0-9a-fA-F]+$")
KEY_RE = re.compile(r"^(?P<key>\"(?:[^\"\\]|\\.)*\"|'(?:[^']|'')*'|[^\s#'\"\[\]{},:][^#:]*?)\s*:(?:\s+|$)(?P<rest>.*)$")


class YamlError(ValueError):
    pass


# Writing

def dump(value, header=None):
    lines = []
    for line in (header or "").splitlines():
        lines.append(("# " + line).rstrip() if line else "#")
    if lines:
        lines.append("")
    if isinstance(value, (dict, list)) and value:
        _block(value, 0, lines)
    else:
        lines.append(_scalar(value))
    return "\n".join(lines) + "\n"


def _block(value, indent, lines):
    pad = " " * indent
    if isinstance(value, dict):
        for key, item in value.items():
            k = _key(key)
            if _inline(item):
                lines.append(f"{pad}{k}: {_inline_text(item)}".rstrip())
            else:
                lines.append(f"{pad}{k}:")
                _block(item, indent + INDENT, lines)
        return
    for item in value:
        if _inline(item):
            lines.append(f"{pad}- {_inline_text(item)}".rstrip())
        elif isinstance(item, dict):
            sub = []
            _block(item, indent + INDENT, sub)
            sub[0] = pad + "- " + sub[0][indent + INDENT:]
            lines.extend(sub)
        else:  # a list inside a list
            lines.append(f"{pad}-")
            _block(item, indent + INDENT, lines)


def _inline(value):
    if isinstance(value, (dict, list)):
        if not value:
            return True
        if isinstance(value, list) and all(_is_scalar(v) for v in value):
            return len(_flow(value)) <= FLOW_WIDTH
        return False
    return True


def _inline_text(value):
    if isinstance(value, dict):
        return "{}"
    if isinstance(value, list):
        return _flow(value)
    return _scalar(value)


def _flow(values):
    return "[" + ", ".join(_scalar(v, flow=True) for v in values) + "]"


def _is_scalar(value):
    return value is None or isinstance(value, (str, int, float, bool))


def _key(key):
    text = str(key)
    return text if PLAIN_RE.match(text) and _plain_ok(text) and " " not in text else _quote(text)


def _scalar(value, flow=False):
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        if value != value or value in (float("inf"), float("-inf")):
            raise YamlError("not a finite number")
        return repr(value)
    if isinstance(value, (dict, list)):
        raise YamlError("nested collections are written in block style")
    text = str(value)
    if PLAIN_RE.match(text) and _plain_ok(text) and not (flow and ("," in text)):
        return text
    return _quote(text)


def _plain_ok(text):
    return (text.lower() not in RESERVED and not NUMBER_RE.match(text) and not HEX_RE.match(text)
            and text == text.strip() and " #" not in text and not text.startswith(("-", "? ")))


def _quote(text):
    return json.dumps(text, ensure_ascii=False)


# Reading

def load(text):
    lines = []
    for number, raw in enumerate(text.splitlines(), 1):
        if raw.strip() in ("---", "..."):
            if lines:
                raise YamlError(f"line {number}: only one document per file")
            continue
        if "\t" in raw[:len(raw) - len(raw.lstrip())]:
            raise YamlError(f"line {number}: tab in indentation")
        lines.append((number, raw))
    parser = _Parser(lines)
    value = parser.value(0)
    parser.skip_blank()
    if parser.i < len(parser.lines):
        number, raw = parser.lines[parser.i]
        raise YamlError(f"line {number}: unexpected indentation: {raw.strip()[:40]}")
    return value


class _Parser:
    def __init__(self, lines):
        self.lines = lines
        self.i = 0

    def skip_blank(self):
        while self.i < len(self.lines) and not _strip_comment(self.lines[self.i][1]).strip():
            self.i += 1

    def peek(self):
        self.skip_blank()
        if self.i >= len(self.lines):
            return None
        number, raw = self.lines[self.i]
        content = _strip_comment(raw)
        return number, len(content) - len(content.lstrip()), content.strip()

    def value(self, min_indent):
        head = self.peek()
        if head is None or head[1] < min_indent:
            return None
        number, indent, text = head
        if text == "-" or text.startswith("- "):
            return self.sequence(indent)
        if KEY_RE.match(text):
            return self.mapping(indent)
        self.i += 1
        return _parse_scalar(text, number)

    def mapping(self, indent):
        result = {}
        while True:
            head = self.peek()
            if head is None or head[1] < indent:
                return result
            number, at, text = head
            if at > indent:
                raise YamlError(f"line {number}: unexpected indentation")
            m = KEY_RE.match(text)
            if not m or text.startswith("- ") or text == "-":
                return result if result else self._fail(number, text)
            key = _parse_key(m.group("key"), number)
            if key in result:
                raise YamlError(f"line {number}: duplicate key {key!r}")
            self.i += 1
            result[key] = self.after_key(m.group("rest").strip(), indent, number)

    def after_key(self, rest, indent, number):
        if rest in ("|", "|-", "|+", ">", ">-", ">+"):
            return self.block_text(rest, indent)
        if rest:
            return _parse_scalar(rest, number)
        head = self.peek()
        if head is None:
            return None
        if head[1] > indent:
            return self.value(head[1])
        if head[1] == indent and (head[2] == "-" or head[2].startswith("- ")):
            return self.sequence(indent)  # "key:" followed by a list at the same indentation
        return None

    def sequence(self, indent):
        result = []
        while True:
            head = self.peek()
            if head is None or head[1] < indent:
                return result
            number, at, text = head
            if at > indent:
                raise YamlError(f"line {number}: unexpected indentation")
            if not (text == "-" or text.startswith("- ")):
                return result
            rest = text[1:].strip()
            if not rest:
                self.i += 1
                head = self.peek()
                result.append(self.value(head[1]) if head and head[1] > indent else None)
            elif KEY_RE.match(rest) and not rest.startswith(("[", "{", '"', "'")) or _quoted_key(rest):
                # "- key: value" starts a mapping whose other keys sit under the key's column
                column = at + (len(text) - len(text[1:].lstrip()))
                number_, raw = self.lines[self.i]
                self.lines[self.i] = (number_, " " * column + rest)
                result.append(self.mapping(column))
            else:
                self.i += 1
                result.append(_parse_scalar(rest, number))

    def block_text(self, style, indent):
        collected = []
        block_indent = None
        while self.i < len(self.lines):
            number, raw = self.lines[self.i]
            if not raw.strip():
                collected.append("")
                self.i += 1
                continue
            at = len(raw) - len(raw.lstrip())
            if at <= indent:
                break
            if block_indent is None:
                block_indent = at
            if at < block_indent:
                break
            collected.append(raw[block_indent:])
            self.i += 1
        while collected and collected[-1] == "":
            collected.pop()
        if style.startswith(">"):
            text = _fold(collected)
        else:
            text = "\n".join(collected)
        return text if style.endswith("-") else text + "\n" if text else ""

    def _fail(self, number, text):
        raise YamlError(f"line {number}: expected 'key: value', got {text[:40]!r}")


def _quoted_key(text):
    m = KEY_RE.match(text)
    return bool(m) and text[:1] in "\"'" and m.group("key")[-1:] in "\"'"


def _fold(lines):
    out, para = [], []
    for line in lines:
        if line == "":
            out.append(" ".join(para))
            para = []
        else:
            para.append(line.strip())
    out.append(" ".join(para))
    return "\n".join(p for p in out)


def _strip_comment(raw):
    """Drop a # comment that is outside quotes (a # starts a comment at line start or after whitespace)."""
    quote = None
    i = 0
    while i < len(raw):
        ch = raw[i]
        if quote == "'":
            if ch == "'":
                if raw[i + 1:i + 2] == "'":  # '' is a quote inside single-quoted text
                    i += 1
                else:
                    quote = None
        elif quote == '"':
            if ch == '"' and not _escaped(raw, i):
                quote = None
        elif ch in "\"'" and (i == 0 or raw[i - 1] in " \t:-[{,"):
            quote = ch
        elif ch == "#" and (i == 0 or raw[i - 1] in " \t"):
            return raw[:i].rstrip()
        i += 1
    return raw.rstrip()


def _escaped(raw, i):
    backslashes = 0
    j = i - 1
    while j >= 0 and raw[j] == "\\":
        backslashes += 1
        j -= 1
    return backslashes % 2 == 1


def _parse_key(text, number):
    value = _parse_scalar(text, number)
    if isinstance(value, (dict, list)):
        raise YamlError(f"line {number}: a key must be a scalar")
    return value if isinstance(value, str) else str(value) if value is not None else "null"


def _parse_scalar(text, number):
    text = text.strip()
    if not text:
        return None
    if text[0] == '"':
        return _double(text, number)
    if text[0] == "'":
        if len(text) < 2 or text[-1] != "'":
            raise YamlError(f"line {number}: unterminated quoted text")
        return text[1:-1].replace("''", "'")
    if text[0] == "[":
        if text[-1] != "]":
            raise YamlError(f"line {number}: a flow list must end on the same line")
        return [_parse_scalar(part, number) for part in _split_flow(text[1:-1], number)]
    if text[0] == "{":
        if text[-1] != "}":
            raise YamlError(f"line {number}: a flow map must end on the same line")
        result = {}
        for part in _split_flow(text[1:-1], number):
            m = KEY_RE.match(part + (" " if part.endswith(":") else ""))
            if not m:
                raise YamlError(f"line {number}: expected 'key: value' in a flow map")
            result[_parse_key(m.group("key"), number)] = _parse_scalar(m.group("rest"), number)
        return result
    if text[0] in "&*!|>%@`":
        raise YamlError(f"line {number}: unsupported YAML ({text[:20]!r})")
    low = text.lower()
    if low in ("null", "~"):
        return None
    if low == "true":
        return True
    if low == "false":
        return False
    if INT_RE.match(text):
        return int(text)
    if NUMBER_RE.match(text) and "_" not in text:
        try:
            return float(text)
        except ValueError:
            return text
    return text


def _double(text, number):
    end = None
    for i in range(1, len(text)):
        if text[i] == '"' and not _escaped(text, i):
            end = i
            break
    if end is None or text[end + 1:].strip():
        raise YamlError(f"line {number}: unterminated or trailing text after a quoted string")
    try:
        return json.loads(text[:end + 1], strict=False)  # dump() writes JSON strings; YAML reads them the same
    except ValueError as e:
        raise YamlError(f"line {number}: bad escape in quoted text ({e})") from None


def _split_flow(text, number):
    parts, buf, quote = [], [], None
    for i, ch in enumerate(text):
        if quote:
            buf.append(ch)
            if ch == quote and not (quote == '"' and _escaped(text, i)):
                quote = None
            continue
        if ch in "\"'":
            quote = ch
        elif ch in "[{":
            raise YamlError(f"line {number}: nested flow collections are not supported")
        elif ch == ",":
            parts.append("".join(buf).strip())
            buf = []
            continue
        buf.append(ch)
    if quote:
        raise YamlError(f"line {number}: unterminated quoted text in a flow collection")
    parts.append("".join(buf).strip())
    if parts and parts[-1] == "":
        parts.pop()  # a trailing comma
    if any(p == "" for p in parts):
        raise YamlError(f"line {number}: empty entry in a flow collection")
    return parts

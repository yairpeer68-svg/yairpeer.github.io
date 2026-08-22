from __future__ import annotations

import hashlib
import json
import math
import re
from dataclasses import dataclass
from pathlib import Path

from app.engineering.workspace import Workspace

_INDEX_DIR = '.ai-platform'
_INDEX_FILE = 'code-index.json'
_VECTOR_DIMS = 256
_TEXT_SUFFIXES = {
    '.py','.pyi','.js','.jsx','.ts','.tsx','.dart','.kt','.kts','.java','.go','.rs','.rb','.php',
    '.swift','.c','.h','.cc','.cpp','.hpp','.cs','.sql','.sh','.ps1','.md','.toml','.yaml','.yml',
    '.json','.gradle','.xml','.html','.css','.scss','.txt'
}
_TOKEN_RE = re.compile(r'[A-Za-z_][A-Za-z0-9_]{1,63}|[0-9]{2,}')
_SYMBOL_PATTERNS = [
    re.compile(r'^\s*(?:async\s+)?def\s+([A-Za-z_][A-Za-z0-9_]*)', re.M),
    re.compile(r'^\s*class\s+([A-Za-z_][A-Za-z0-9_]*)', re.M),
    re.compile(r'\b(?:function|class|interface|type|enum)\s+([A-Za-z_$][A-Za-z0-9_$]*)'),
    re.compile(r'\b(?:fun|class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)'),
]


def _split_identifier(token: str) -> list[str]:
    token = token.replace('-', '_')
    parts: list[str] = []
    for bit in token.split('_'):
        if not bit:
            continue
        parts.extend(re.findall(r'[A-Z]?[a-z]+|[A-Z]+(?=[A-Z]|$)|\d+', bit) or [bit])
    return [p.lower() for p in parts if len(p) > 1]


def _tokens(text: str) -> list[str]:
    out: list[str] = []
    for raw in _TOKEN_RE.findall(text[:250_000]):
        out.append(raw.lower())
        out.extend(_split_identifier(raw))
    return out


def _vector(tokens: list[str]) -> dict[str, float]:
    values: dict[int, float] = {}
    for token in tokens:
        digest = hashlib.blake2b(token.encode('utf-8'), digest_size=8).digest()
        bucket = int.from_bytes(digest, 'big') % _VECTOR_DIMS
        sign = -1.0 if digest[0] & 1 else 1.0
        values[bucket] = values.get(bucket, 0.0) + sign
    norm = math.sqrt(sum(v * v for v in values.values())) or 1.0
    return {str(k): round(v / norm, 8) for k, v in values.items() if v}


def _cosine(left: dict[str, float], right: dict[str, float]) -> float:
    if len(left) > len(right):
        left, right = right, left
    return sum(v * right.get(k, 0.0) for k, v in left.items())


def _symbols(text: str) -> list[str]:
    seen: set[str] = set()
    for pattern in _SYMBOL_PATTERNS:
        for item in pattern.findall(text[:250_000]):
            if item not in seen:
                seen.add(item)
                if len(seen) >= 120:
                    return list(seen)
    return list(seen)


def _language(path: Path) -> str:
    mapping = {
        '.py':'python','.pyi':'python','.js':'javascript','.jsx':'javascript','.ts':'typescript','.tsx':'typescript',
        '.dart':'dart','.kt':'kotlin','.kts':'kotlin','.java':'java','.go':'go','.rs':'rust','.rb':'ruby',
        '.php':'php','.swift':'swift','.c':'c','.h':'c','.cc':'cpp','.cpp':'cpp','.hpp':'cpp','.cs':'csharp',
        '.sql':'sql','.sh':'shell','.ps1':'powershell','.md':'markdown','.json':'json','.yaml':'yaml','.yml':'yaml',
        '.toml':'toml','.gradle':'gradle','.xml':'xml','.html':'html','.css':'css','.scss':'scss',
    }
    return mapping.get(path.suffix.lower(), 'text')


@dataclass(frozen=True)
class SearchHit:
    path: str
    score: float
    language: str
    symbols: list[str]
    excerpt: str


class CodeIndex:
    """Local sparse-embedding code index.

    It intentionally avoids sending source code to a third-party embedding service. Feature hashing gives
    deterministic sparse vectors over identifiers, paths and symbols; symbol/path boosts improve code search.
    """

    def __init__(self, workspace: Workspace):
        self.workspace = workspace
        self.meta_dir = workspace.root / _INDEX_DIR
        self.path = self.meta_dir / _INDEX_FILE

    def _fingerprint(self) -> str:
        """Cheap staleness signal: path, size and mtime of every indexed file.

        manifest_hash() reads every byte of the workspace, which made each code search
        an O(repository) hash. The index only needs to know whether content changed.
        """
        h = hashlib.sha256()
        for file in sorted(self.workspace.files()):
            try:
                stat = file.stat()
            except OSError:
                continue
            rel = str(file.relative_to(self.workspace.root)).replace('\\', '/')
            h.update(f'{rel}\0{stat.st_size}\0{int(stat.st_mtime_ns)}\0'.encode())
        return h.hexdigest()

    def rebuild(self) -> dict:
        records: list[dict] = []
        for file in self.workspace.files():
            if file.suffix.lower() not in _TEXT_SUFFIXES:
                continue
            try:
                if file.stat().st_size > min(self.workspace.settings.ENGINEERING_MAX_FILE_BYTES, 1_000_000):
                    continue
                text = file.read_text(encoding='utf-8')
            except (OSError, UnicodeDecodeError):
                continue
            rel = str(file.relative_to(self.workspace.root)).replace('\\', '/')
            symbols = _symbols(text)
            token_stream = _tokens(rel.replace('/', ' ') + '\n' + ' '.join(symbols) + '\n' + text)
            records.append({
                'path': rel,
                'sha256': hashlib.sha256(text.encode('utf-8')).hexdigest(),
                'language': _language(file),
                'symbols': symbols,
                'vector': _vector(token_stream),
            })
        payload = {
            'version': 2,
            'manifest_hash': self.workspace.manifest_hash(),
            'fingerprint': self._fingerprint(),
            'files': records,
        }
        self.meta_dir.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix('.tmp')
        tmp.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
        tmp.replace(self.path)
        return {'files_indexed': len(records), 'manifest_hash': payload['manifest_hash'], 'version': 2}

    def _data(self) -> dict:
        """Return a fresh index, rebuilding only when the workspace fingerprint moved."""
        try:
            data = json.loads(self.path.read_text(encoding='utf-8'))
            if data.get('version') == 2 and data.get('fingerprint') == self._fingerprint():
                return data
        except (OSError, ValueError, TypeError):
            pass
        self.rebuild()
        try:
            return json.loads(self.path.read_text(encoding='utf-8'))
        except (OSError, ValueError, TypeError):
            return {'version': 2, 'files': []}

    def search(self, query: str, limit: int = 12) -> list[SearchHit]:
        query = query.strip()
        if not query:
            return []
        q_tokens = _tokens(query)
        q_vec = _vector(q_tokens)
        q_terms = {x.lower() for x in q_tokens}
        scored: list[tuple[float, dict]] = []
        for record in self._data().get('files', []):
            vector = {str(k): float(v) for k, v in (record.get('vector') or {}).items()}
            score = _cosine(q_vec, vector)
            path_lower = str(record.get('path', '')).lower()
            symbols_lower = {str(x).lower() for x in record.get('symbols', [])}
            score += 0.18 * sum(1 for term in q_terms if term in path_lower)
            score += 0.12 * sum(1 for term in q_terms if any(term in s for s in symbols_lower))
            if score > 0.01:
                scored.append((score, record))
        scored.sort(key=lambda item: item[0], reverse=True)
        hits: list[SearchHit] = []
        for score, record in scored[:max(1, min(limit, 50))]:
            try:
                text = self.workspace.read_text(str(record['path']))
            except Exception:
                continue
            excerpt = self._excerpt(text, q_terms)
            hits.append(SearchHit(
                path=str(record['path']), score=round(float(score), 5), language=str(record.get('language', 'text')),
                symbols=[str(x) for x in record.get('symbols', [])[:40]], excerpt=excerpt,
            ))
        return hits

    @staticmethod
    def _excerpt(text: str, terms: set[str], max_chars: int = 2400) -> str:
        if len(text) <= max_chars:
            return text
        lower = text.lower()
        positions = [lower.find(term) for term in terms if len(term) > 2 and lower.find(term) >= 0]
        start = max(0, (min(positions) if positions else 0) - 400)
        return text[start:start + max_chars]

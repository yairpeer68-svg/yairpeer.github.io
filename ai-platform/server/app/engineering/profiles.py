from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

SKIP = {'.git','.ai-platform','node_modules','build','dist','.dart_tool','.gradle','.venv','venv','__pycache__'}

@dataclass(frozen=True)
class BuildCommand:
    name: str
    argv: list[str]
    cwd: str = '.'
    required: bool = True

@dataclass(frozen=True)
class BuildTarget:
    kind: str
    cwd: str
    commands: tuple[BuildCommand, ...]


def _rel(root: Path, p: Path) -> str:
    value = str(p.relative_to(root)).replace('\\','/')
    return value or '.'


def _candidate_dirs(root: Path, max_depth: int = 3) -> list[Path]:
    dirs = {root}
    for p in root.rglob('*'):
        try:
            rel = p.relative_to(root)
        except ValueError:
            continue
        if len(rel.parts) > max_depth:
            continue
        if any(part in SKIP for part in rel.parts):
            continue
        if p.is_dir():
            dirs.add(p)
    return sorted(dirs, key=lambda p: (len(p.relative_to(root).parts), str(p)))


def detect_build_targets(root: Path) -> list[BuildTarget]:
    targets: list[BuildTarget] = []
    seen: set[tuple[str, str]] = set()
    for directory in _candidate_dirs(root):
        cwd = _rel(root, directory)

        py_marker = any((directory / x).exists() for x in ('pyproject.toml','pytest.ini','requirements.txt','setup.py'))
        py_files = any(directory.glob('*.py'))
        if py_marker or py_files:
            python_commands = [BuildCommand('python_compile',['python','-m','compileall','-q','.'],cwd)]
            if (directory/'tests').exists() or (directory/'pytest.ini').exists() or (directory/'pyproject.toml').exists():
                python_commands.append(BuildCommand('pytest',['python','-m','pytest','-q'],cwd))
            key=('python',cwd)
            if key not in seen:
                targets.append(BuildTarget('python',cwd,tuple(python_commands))); seen.add(key)

        package = directory/'package.json'
        if package.exists():
            node_commands: list[BuildCommand] = []
            try:
                data=json.loads(package.read_text(encoding='utf-8')); scripts=data.get('scripts',{}) if isinstance(data,dict) else {}
            except Exception:
                scripts={}
            if (directory/'node_modules').exists():
                for script in ('lint','test','build'):
                    if isinstance(scripts,dict) and script in scripts:
                        node_commands.append(BuildCommand(f'npm_{script}',['npm','run',script],cwd))
            else:
                node_commands.append(BuildCommand('node_dependencies',['npm','--version'],cwd,required=False))
            key=('node',cwd)
            if key not in seen:
                targets.append(BuildTarget('node',cwd,tuple(node_commands))); seen.add(key)

        if (directory/'pubspec.yaml').exists():
            key=('flutter',cwd)
            if key not in seen:
                targets.append(BuildTarget('flutter',cwd,(
                    BuildCommand('flutter_analyze',['flutter','analyze'],cwd),
                    BuildCommand('flutter_test',['flutter','test'],cwd),
                ))); seen.add(key)

        gradlew=directory/'gradlew'
        if gradlew.exists():
            key=('gradle',cwd)
            if key not in seen:
                targets.append(BuildTarget('gradle',cwd,(
                    BuildCommand('gradle_test',['./gradlew','test','--no-daemon'],cwd),
                ))); seen.add(key)
    return targets

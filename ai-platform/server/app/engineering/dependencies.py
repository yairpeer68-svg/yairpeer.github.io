from __future__ import annotations

import json
import re
import tomllib
from pathlib import Path

from app.engineering.workspace import Workspace


def _warning(severity: str, rule: str, path: str, detail: str) -> dict:
    return {'severity': severity, 'rule': rule, 'path': path, 'detail': detail[:500]}


def analyze_dependencies(ws: Workspace) -> dict:
    dependencies: list[dict] = []
    warnings: list[dict] = []
    manifests = 0
    lockfiles = {str(p.relative_to(ws.root)).replace('\\','/') for p in ws.files() if p.name in {'package-lock.json','pnpm-lock.yaml','yarn.lock','poetry.lock','uv.lock','Pipfile.lock','pubspec.lock','gradle.lockfile'}}

    for p in ws.files():
        rel = str(p.relative_to(ws.root)).replace('\\','/')
        name = p.name.lower()
        try:
            text = p.read_text(encoding='utf-8')
        except (OSError, UnicodeDecodeError):
            continue

        if name.startswith('requirements') and name.endswith('.txt'):
            manifests += 1
            for line in text.splitlines():
                item = line.strip()
                if not item or item.startswith('#') or item.startswith(('-r ','--')):
                    continue
                dependencies.append({'ecosystem':'python','manifest':rel,'declaration':item[:300]})
                if item.startswith(('http://','https://','git+')):
                    warnings.append(_warning('medium','direct_dependency_url',rel,item))
                elif not re.search(r'(===|==|~=|>=|<=|>|<)', item):
                    warnings.append(_warning('low','python_unpinned_dependency',rel,item))

        elif name == 'pyproject.toml':
            manifests += 1
            try:
                data = tomllib.loads(text)
                values = data.get('project',{}).get('dependencies',[]) if isinstance(data,dict) else []
                for item in values if isinstance(values,list) else []:
                    if isinstance(item,str): dependencies.append({'ecosystem':'python','manifest':rel,'declaration':item[:300]})
            except Exception:
                warnings.append(_warning('medium','invalid_pyproject',rel,'Unable to parse pyproject.toml'))

        elif name == 'package.json':
            manifests += 1
            try:
                data=json.loads(text)
                for group in ('dependencies','devDependencies','peerDependencies'):
                    values=data.get(group,{}) if isinstance(data,dict) else {}
                    if not isinstance(values,dict): continue
                    for package,version in values.items():
                        declaration=f'{package}@{version}'
                        dependencies.append({'ecosystem':'node','manifest':rel,'group':group,'declaration':declaration[:300]})
                        version=str(version)
                        if version in {'*','latest'}:
                            warnings.append(_warning('medium','node_floating_dependency',rel,declaration))
                        if version.startswith(('http://','git+http://')):
                            warnings.append(_warning('high','insecure_dependency_source',rel,declaration))
                parent=str(Path(rel).parent).replace('\\','/')
                expected_node_locks={f'{parent}/{x}'.lstrip('./') for x in ('package-lock.json','pnpm-lock.yaml','yarn.lock')}
                if not any(x in lockfiles for x in expected_node_locks): warnings.append(_warning('low','node_lockfile_missing',rel,'No Node lockfile found next to package.json'))
            except Exception:
                warnings.append(_warning('medium','invalid_package_json',rel,'Unable to parse package.json'))

        elif name == 'pubspec.yaml':
            manifests += 1
            section=None
            for line in text.splitlines():
                if re.match(r'^(dependencies|dev_dependencies):\s*$',line): section=line.split(':',1)[0]; continue
                if section and re.match(r'^\S',line): section=None
                if section:
                    m=re.match(r'^\s{2}([A-Za-z0-9_\-]+):\s*(.+)?$',line)
                    if m and m.group(1)!='flutter':
                        value=(m.group(2) or '').strip(); declaration=f'{m.group(1)}:{value}'
                        dependencies.append({'ecosystem':'dart','manifest':rel,'group':section,'declaration':declaration[:300]})
            parent=str(Path(rel).parent).replace('\\','/')
            expected_dart_lock=f'{parent}/pubspec.lock'.lstrip('./')
            if expected_dart_lock not in lockfiles: warnings.append(_warning('low','dart_lockfile_missing',rel,'pubspec.lock not found next to pubspec.yaml'))

        elif name in {'build.gradle','build.gradle.kts'}:
            manifests += 1
            for match in re.findall(r'''(?:implementation|api|testImplementation)\s*\(?["']([^"']+)["']''',text):
                dependencies.append({'ecosystem':'gradle','manifest':rel,'declaration':match[:300]})
                if match.endswith(':+') or ':latest.' in match.lower(): warnings.append(_warning('medium','gradle_dynamic_version',rel,match))

    return {
        'manifests': manifests,
        'dependencies': dependencies[:2000],
        'dependency_count': len(dependencies),
        'warnings': warnings[:500],
        'high': sum(w['severity']=='high' for w in warnings),
        'medium': sum(w['severity']=='medium' for w in warnings),
        'low': sum(w['severity']=='low' for w in warnings),
        'offline_note': 'Manifest and reproducibility analysis only; CVE freshness requires a separately configured vulnerability database/feed.',
    }

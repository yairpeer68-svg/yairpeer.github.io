from __future__ import annotations

from app.engineering.executor import SafeExecutor
from app.engineering.profiles import detect_build_targets


async def run_quality_gates(executor: SafeExecutor) -> dict:
    """Execute the detected build/test gates.

    A gate whose toolchain is absent from the runner image is reported as
    ``toolchain_missing``. Whether that blocks the run is a deployment decision:
    ``ENGINEERING_STRICT_TOOLCHAINS=true`` treats it as a failure (fail closed),
    otherwise the run continues with the gate recorded as unverified. The default is
    permissive because the stock runner image ships Python/Node only, so a Flutter or
    Gradle project would otherwise never be able to complete a run.
    """
    gates: list[dict] = []
    targets = detect_build_targets(executor.root)
    strict = executor.settings.ENGINEERING_STRICT_TOOLCHAINS

    async def gate(target_kind: str, name: str, cmd: list[str], cwd: str, required: bool):
        result = await executor.run(cmd, cwd=cwd)
        passed = result.returncode == 0 and not result.skipped
        gates.append({
            'target': target_kind,
            'name': name,
            'cwd': cwd,
            'command': cmd,
            'returncode': result.returncode,
            'passed': passed,
            'required': required,
            'skipped': result.skipped,
            'toolchain_missing': result.tool_missing,
            'reason': result.reason,
            'stdout': result.stdout[-6000:],
            'stderr': result.stderr[-6000:],
        })

    for target in targets:
        for command in target.commands:
            await gate(target.kind, command.name, command.argv, command.cwd, command.required)

    missing_toolchains = sorted({g['command'][0] for g in gates if g.get('toolchain_missing')})
    unverified = [g for g in gates if g.get('required') and g.get('toolchain_missing')]
    blocking_skips = [
        g for g in gates
        if g.get('required') and g.get('skipped') and (strict or not g.get('toolchain_missing'))
    ]
    failures = [g for g in gates if g.get('required') and not g.get('skipped') and not g.get('passed')]
    passed = not blocking_skips and not failures
    if not targets:
        passed = True
    return {
        'passed': passed,
        'verified': bool(gates) and not blocking_skips and not unverified,
        'strict_toolchains': strict,
        'targets': [{'kind': t.kind, 'cwd': t.cwd} for t in targets],
        'gates': gates,
        'executed': sum(not g.get('skipped') for g in gates),
        'skipped': sum(bool(g.get('skipped')) for g in gates),
        'blocking_skips': len(blocking_skips),
        'unverified_gates': len(unverified),
        'missing_toolchains': missing_toolchains,
        'failures': len(failures),
    }

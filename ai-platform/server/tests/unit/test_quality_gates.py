from dataclasses import dataclass
from pathlib import Path

import pytest

from app.core.config import Settings
from app.engineering.executor import CommandResult
from app.engineering.quality import run_quality_gates
from app.engineering.profiles import BuildCommand, BuildTarget


@dataclass
class _FakeExecutor:
    settings: Settings
    root: Path
    results: dict

    async def run(self, argv, timeout=None, cwd=None):
        return self.results.get(argv[0], CommandResult(argv, 0, '', ''))


def _settings(tmp_path, strict: bool):
    return Settings(APP_ENV='test', AI_PROVIDER_MODE='mock', JWT_SECRET='x' * 64,
                    ENGINEERING_WORKSPACE_ROOT=str(tmp_path),
                    ENGINEERING_STRICT_TOOLCHAINS=strict)


@pytest.fixture
def flutter_target(monkeypatch):
    target = BuildTarget('flutter', '.', (
        BuildCommand('flutter_analyze', ['flutter', 'analyze'], '.'),
        BuildCommand('flutter_test', ['flutter', 'test'], '.'),
    ))
    monkeypatch.setattr('app.engineering.quality.detect_build_targets', lambda root: [target])
    return target


async def test_missing_toolchain_does_not_fail_the_run_by_default(tmp_path, flutter_target):
    """The stock runner image has no Flutter SDK; that must not make every run fail."""
    missing = CommandResult(['flutter'], 127, '', '', True, 'tool is not installed')
    executor = _FakeExecutor(_settings(tmp_path, strict=False), tmp_path, {'flutter': missing})

    result = await run_quality_gates(executor)

    assert result['passed'] is True
    assert result['verified'] is False
    assert result['missing_toolchains'] == ['flutter']
    assert result['unverified_gates'] == 2


async def test_strict_mode_treats_a_missing_toolchain_as_a_blocker(tmp_path, flutter_target):
    missing = CommandResult(['flutter'], 127, '', '', True, 'tool is not installed')
    executor = _FakeExecutor(_settings(tmp_path, strict=True), tmp_path, {'flutter': missing})

    result = await run_quality_gates(executor)

    assert result['passed'] is False
    assert result['blocking_skips'] == 2


async def test_a_real_test_failure_still_fails_in_permissive_mode(tmp_path, flutter_target):
    failure = CommandResult(['flutter'], 1, '', '2 tests failed')
    executor = _FakeExecutor(_settings(tmp_path, strict=False), tmp_path, {'flutter': failure})

    result = await run_quality_gates(executor)

    assert result['passed'] is False
    assert result['failures'] == 2


async def test_a_policy_block_is_never_excused_as_a_missing_toolchain(tmp_path, flutter_target):
    blocked = CommandResult(['flutter'], 126, '', '', True, 'command is not allow-listed')
    executor = _FakeExecutor(_settings(tmp_path, strict=False), tmp_path, {'flutter': blocked})

    result = await run_quality_gates(executor)

    assert result['passed'] is False
    assert result['blocking_skips'] == 2
    assert result['missing_toolchains'] == []


async def test_no_detected_targets_passes(tmp_path, monkeypatch):
    monkeypatch.setattr('app.engineering.quality.detect_build_targets', lambda root: [])
    executor = _FakeExecutor(_settings(tmp_path, strict=True), tmp_path, {})
    result = await run_quality_gates(executor)
    assert result['passed'] is True
    assert result['gates'] == []

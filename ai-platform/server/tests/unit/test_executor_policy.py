from pathlib import Path

import pytest

from app.core.config import Settings
from app.engineering.executor import SafeExecutor


@pytest.fixture
def executor(tmp_path):
    return SafeExecutor(
        Settings(APP_ENV='test', AI_PROVIDER_MODE='mock', JWT_SECRET='x' * 64,
                 ENGINEERING_WORKSPACE_ROOT=str(tmp_path)),
        Path(tmp_path),
    )


@pytest.mark.parametrize('argv', [
    ['python', '-V'],
    ['python', '-m', 'pytest', '-q'],
    ['npm', 'run', 'test'],
    ['git', 'status'],
])
def test_ordinary_toolchain_invocations_are_allowed(executor, argv):
    allowed, reason = executor.classify(argv)
    assert allowed, reason


@pytest.mark.parametrize('argv', [
    ['bash', '-c', 'rm -rf /'],
    ['sh', '-c', 'id'],
    ['curl', 'https://example.invalid'],
    ['rm', '-rf', '.'],
])
def test_non_allow_listed_and_blocked_tools_are_refused(executor, argv):
    allowed, _ = executor.classify(argv)
    assert not allowed


@pytest.mark.parametrize('argv', [
    ['python', '-c', 'import os; os.system("id")'],
    ['python3', '--command=print(1)'],
    ['node', '-e', 'process.exit(1)'],
    ['node', '--eval', 'require("fs")'],
    ['node', '-r', './payload.js', 'app.js'],
])
def test_inline_code_execution_is_refused(executor, argv):
    """An allow-listed interpreter handed inline source is an arbitrary-code primitive."""
    allowed, reason = executor.classify(argv)
    assert not allowed
    assert reason == 'inline code execution is blocked by execution policy'


@pytest.mark.parametrize('argv', [
    ['git', '-c', 'core.gitProxy=/tmp/payload', 'fetch'],
    ['git', '--exec-path=/tmp', 'status'],
    ['git', 'clone', '--upload-pack', '/tmp/payload', 'x'],
])
def test_git_configuration_overrides_are_refused(executor, argv):
    allowed, reason = executor.classify(argv)
    assert not allowed
    assert reason == 'git configuration override is blocked by execution policy'


def test_argument_count_and_length_are_bounded(executor):
    assert not executor.classify(['python'] + ['-x'] * 100)[0]
    assert not executor.classify(['python', 'a' * 5000])[0]
    assert not executor.classify([])[0]


def test_cwd_cannot_escape_the_workspace(executor, tmp_path):
    (tmp_path / 'sub').mkdir()
    assert executor._cwd('sub') == (tmp_path / 'sub').resolve()
    assert executor._cwd('../../etc') == executor.root
    assert executor._cwd('/etc') == executor.root
    assert executor._cwd(None) == executor.root

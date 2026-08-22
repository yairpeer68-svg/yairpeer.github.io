import httpx
import pytest

from app.ai.deepseek import DeepSeekProvider
from app.core.config import Settings
from app.core.errors import AppError

MESSAGES = [{'role': 'user', 'content': 'hello'}]


def settings():
    return Settings(APP_ENV='test', JWT_SECRET='x' * 64, DEEPSEEK_API_KEY='k',
                    DEEPSEEK_MAX_RETRIES=2)


def _client(handler):
    return httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url='https://api.deepseek.test')


async def test_a_read_timeout_is_not_retried(monkeypatch):
    """The provider already accepted the request; a retry can bill a second completion."""
    calls = {'n': 0}

    def handler(request):
        calls['n'] += 1
        raise httpx.ReadTimeout('timed out', request=request)

    async with _client(handler) as client:
        with pytest.raises(AppError) as excinfo:
            await DeepSeekProvider(settings(), client).chat(MESSAGES, 'deepseek-chat', 0.1, 64)
    assert excinfo.value.code == 'AI_PROVIDER_TIMEOUT'
    assert calls['n'] == 1


async def test_a_connect_error_is_retried(monkeypatch):
    """Nothing reached the provider, so retrying cannot double-charge."""
    monkeypatch.setattr('app.ai.deepseek.asyncio.sleep', lambda _: _noop())
    calls = {'n': 0}

    def handler(request):
        calls['n'] += 1
        raise httpx.ConnectError('refused', request=request)

    async with _client(handler) as client:
        with pytest.raises(AppError) as excinfo:
            await DeepSeekProvider(settings(), client).chat(MESSAGES, 'deepseek-chat', 0.1, 64)
    assert excinfo.value.code == 'AI_PROVIDER_TIMEOUT'
    assert calls['n'] == 3  # initial attempt plus DEEPSEEK_MAX_RETRIES


async def _noop():
    return None


async def test_a_successful_response_is_parsed():
    def handler(request):
        return httpx.Response(200, json={
            'model': 'deepseek-chat',
            'choices': [{'message': {'content': 'hi there'}}],
            'usage': {'prompt_tokens': 3, 'completion_tokens': 2, 'total_tokens': 5},
        })

    async with _client(handler) as client:
        result = await DeepSeekProvider(settings(), client).chat(MESSAGES, 'deepseek-chat', 0.1, 64)
    assert result.content == 'hi there'
    assert result.usage.total_tokens == 5


async def test_an_empty_completion_is_rejected():
    def handler(request):
        return httpx.Response(200, json={'choices': [{'message': {'content': '   '}}], 'usage': {}})

    async with _client(handler) as client:
        with pytest.raises(AppError) as excinfo:
            await DeepSeekProvider(settings(), client).chat(MESSAGES, 'deepseek-chat', 0.1, 64)
    assert excinfo.value.code == 'AI_PROVIDER_INVALID_RESPONSE'


async def test_a_client_error_is_not_retried():
    calls = {'n': 0}

    def handler(request):
        calls['n'] += 1
        return httpx.Response(400, json={'error': 'bad request'})

    async with _client(handler) as client:
        with pytest.raises(AppError) as excinfo:
            await DeepSeekProvider(settings(), client).chat(MESSAGES, 'deepseek-chat', 0.1, 64)
    assert excinfo.value.code == 'AI_PROVIDER_REJECTED'
    assert calls['n'] == 1


async def test_a_missing_api_key_fails_before_any_request():
    calls = {'n': 0}

    def handler(request):
        calls['n'] += 1
        return httpx.Response(200, json={})

    async with _client(handler) as client:
        provider = DeepSeekProvider(Settings(APP_ENV='test', JWT_SECRET='x' * 64, DEEPSEEK_API_KEY=''), client)
        with pytest.raises(AppError) as excinfo:
            await provider.chat(MESSAGES, 'deepseek-chat', 0.1, 64)
    assert excinfo.value.code == 'AI_NOT_CONFIGURED'
    assert calls['n'] == 0

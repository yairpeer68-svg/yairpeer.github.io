import httpx
import pytest

from app.ai.deepseek import DeepSeekProvider
from app.core.config import Settings
from app.core.errors import AppError


def cfg(**kw):
    base = dict(APP_ENV="test", JWT_SECRET="x"*80, DEEPSEEK_API_KEY="test-key", DEEPSEEK_MAX_RETRIES=0,
                DEEPSEEK_BASE_URL="https://deepseek.invalid")
    base.update(kw)
    return Settings(**base)


@pytest.mark.asyncio
async def test_provider_success():
    def handler(request: httpx.Request):
        assert request.headers["Authorization"] == "Bearer test-key"
        return httpx.Response(200, json={"model":"deepseek-chat","choices":[{"message":{"content":"hello"}}],
                                         "usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="https://deepseek.invalid") as client:
        out = await DeepSeekProvider(cfg(), client).chat([{"role":"user","content":"hi"}],"deepseek-chat",0.7,128)
    assert out.content == "hello"
    assert out.usage.total_tokens == 3


@pytest.mark.asyncio
async def test_provider_timeout_maps_error():
    def handler(request: httpx.Request):
        raise httpx.ReadTimeout("timeout", request=request)
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="https://deepseek.invalid") as client:
        with pytest.raises(AppError) as exc:
            await DeepSeekProvider(cfg(), client).chat([{"role":"user","content":"hi"}],"deepseek-chat",0.7,128)
    assert exc.value.code == "AI_PROVIDER_TIMEOUT"


@pytest.mark.asyncio
async def test_provider_429_maps_error():
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(429, headers={"Retry-After":"7"})), base_url="https://deepseek.invalid") as client:
        with pytest.raises(AppError) as exc:
            await DeepSeekProvider(cfg(), client).chat([{"role":"user","content":"hi"}],"deepseek-chat",0.7,128)
    assert exc.value.code == "AI_PROVIDER_RATE_LIMIT"
    assert exc.value.headers["Retry-After"] == "7"


@pytest.mark.asyncio
async def test_provider_retries_500(monkeypatch):
    calls = 0
    def handler(request: httpx.Request):
        nonlocal calls; calls += 1
        if calls == 1: return httpx.Response(500)
        return httpx.Response(200,json={"choices":[{"message":{"content":"ok"}}],"usage":{}})
    async def no_sleep(_): return None
    monkeypatch.setattr("app.ai.deepseek.asyncio.sleep", no_sleep)
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="https://deepseek.invalid") as client:
        out=await DeepSeekProvider(cfg(DEEPSEEK_MAX_RETRIES=1),client).chat([{"role":"user","content":"hi"}],"deepseek-chat",0,32)
    assert out.content == "ok" and calls == 2


@pytest.mark.asyncio
async def test_provider_not_configured():
    s=cfg(DEEPSEEK_API_KEY="")
    with pytest.raises(AppError) as exc:
        await DeepSeekProvider(s).chat([{"role":"user","content":"hi"}],"deepseek-chat",0,32)
    assert exc.value.code == "AI_NOT_CONFIGURED"

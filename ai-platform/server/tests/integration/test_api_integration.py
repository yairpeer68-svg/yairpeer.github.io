# ruff: noqa: E402 - settings are read at import time, so the environment must be
# populated before any app module is imported.
import os
if os.getenv("RUN_INTEGRATION") != "1":
    import pytest
    pytest.skip("integration environment not enabled", allow_module_level=True)

import uuid
from datetime import UTC, datetime, timedelta

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy import delete, select

os.environ.setdefault("APP_ENV", "test")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://ai_platform:ai_platform_test@localhost:5432/ai_platform_test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/15")
os.environ.setdefault("JWT_SECRET", "integration-test-secret-" + "x" * 80)
os.environ.setdefault("TRUSTED_HOSTS", "localhost,127.0.0.1")
os.environ.setdefault("CORS_ORIGINS", "http://localhost:5173")
os.environ.setdefault("DEEPSEEK_API_KEY", "integration-mocked-key")

from app.core.config import get_settings
get_settings.cache_clear()
from app.db.base import Base
from app.db.session import get_engine, get_session
from app.main import app
from app.models.entities import AIQuota, RefreshToken, User
from app.security.passwords import hash_password
from app.security.tokens import hash_token
from app.services.redis_service import get_redis_service

pytestmark = pytest.mark.integration


@pytest_asyncio.fixture(autouse=True)
async def clean_database_and_redis():
    engine = get_engine()
    async with engine.begin() as conn:
        for table in reversed(Base.metadata.sorted_tables):
            await conn.execute(delete(table))
    redis = await get_redis_service(get_settings()).client()
    await redis.flushdb()
    yield


@pytest_asyncio.fixture
async def client():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://localhost") as c:
        yield c


async def register_and_login(client, email="user@example.com", password="correct horse battery staple"):
    r = await client.post('/api/v1/auth/register', json={'email': email, 'password': password, 'display_name':'User'})
    assert r.status_code == 201, r.text
    r = await client.post('/api/v1/auth/login', json={'email': email, 'password': password})
    assert r.status_code == 200, r.text
    return r.json()


async def create_admin_and_login(client):
    agen = get_session(); db = await agen.__anext__()
    try:
        admin = User(email='admin@example.com', password_hash=hash_password('admin correct horse battery'), is_admin=True, is_active=True)
        db.add(admin); await db.commit()
    finally:
        await agen.aclose()
    r=await client.post('/api/v1/auth/login',json={'email':'admin@example.com','password':'admin correct horse battery'})
    assert r.status_code==200, r.text
    return r.json()


def auth(pair): return {'Authorization': f"Bearer {pair['access_token']}"}


@pytest.mark.asyncio
async def test_registration_login_invalid_password_and_current_user(client):
    pair=await register_and_login(client)
    me=await client.get('/api/v1/users/me',headers=auth(pair))
    assert me.status_code==200 and me.json()['email']=='user@example.com'
    assert 'password_hash' not in me.json()
    bad=await client.post('/api/v1/auth/login',json={'email':'user@example.com','password':'wrong'})
    assert bad.status_code==401 and bad.json()['error']['code']=='INVALID_CREDENTIALS'


@pytest.mark.asyncio
async def test_refresh_rotation_reuse_detection_and_revocation(client):
    pair=await register_and_login(client)
    first=pair['refresh_token']
    r=await client.post('/api/v1/auth/refresh',json={'refresh_token':first})
    assert r.status_code==200
    second=r.json()['refresh_token']; assert second!=first
    reuse=await client.post('/api/v1/auth/refresh',json={'refresh_token':first})
    assert reuse.status_code==401 and reuse.json()['error']['code']=='REFRESH_TOKEN_REUSE'
    child=await client.post('/api/v1/auth/refresh',json={'refresh_token':second})
    assert child.status_code==401


@pytest.mark.asyncio
async def test_logout_revokes_session(client):
    pair=await register_and_login(client)
    r=await client.post('/api/v1/auth/logout',headers=auth(pair),json={'refresh_token':pair['refresh_token']})
    assert r.status_code==200
    me=await client.get('/api/v1/users/me',headers=auth(pair))
    assert me.status_code==401


@pytest.mark.asyncio
async def test_expired_refresh_token(client):
    pair=await register_and_login(client)
    agen=get_session(); db=await agen.__anext__()
    try:
        token=await db.scalar(select(RefreshToken).where(RefreshToken.token_hash==hash_token(pair['refresh_token'])))
        token.expires_at=datetime.now(UTC)-timedelta(seconds=1); await db.commit()
    finally: await agen.aclose()
    r=await client.post('/api/v1/auth/refresh',json={'refresh_token':pair['refresh_token']})
    assert r.status_code==401 and r.json()['error']['code']=='REFRESH_TOKEN_EXPIRED'


@pytest.mark.asyncio
async def test_device_register_list_revoke(client):
    pair=await register_and_login(client)
    payload={'device_id':'device-12345678','installation_id':'install-12345678','platform':'android','device_name':'Pixel','app_version':'1.0.0','os_version':'16'}
    r=await client.post('/api/v1/devices/register',headers=auth(pair),json=payload)
    assert r.status_code==201 and r.json()['trusted'] is False
    device_id=r.json()['id']
    listed=await client.get('/api/v1/devices',headers=auth(pair)); assert len(listed.json())==1
    revoked=await client.post(f'/api/v1/devices/{device_id}/revoke',headers=auth(pair)); assert revoked.status_code==200


@pytest.mark.asyncio
async def test_admin_authorization_feature_flags_and_sql_injection_safe(client):
    user=await register_and_login(client)
    denied=await client.get('/api/v1/admin/users',headers=auth(user)); assert denied.status_code==403
    admin=await create_admin_and_login(client)
    q=await client.get("/api/v1/admin/users?q=%27%20OR%201%3D1--",headers=auth(admin)); assert q.status_code==200
    flag=await client.post('/api/v1/admin/feature-flags',headers=auth(admin),json={'key':'new_dashboard','enabled':True,'rollout_percentage':100})
    assert flag.status_code==200
    flags=await client.get('/api/v1/feature-flags',headers=auth(user)); assert flags.json()['new_dashboard'] is True


@pytest.mark.asyncio
async def test_rate_limit_returns_429(client):
    await client.post('/api/v1/auth/register',json={'email':'rate@example.com','password':'correct horse battery staple'})
    seen=False
    for _ in range(get_settings().AUTH_RATE_LIMIT_PER_MINUTE + 2):
        r=await client.post('/api/v1/auth/login',json={'email':'rate@example.com','password':'bad password'})
        if r.status_code==429:
            seen=True; assert 'Retry-After' in r.headers; break
    assert seen


@pytest.mark.asyncio
async def test_ai_success_cache_quota_and_prompt_not_stored(client):
    pair=await register_and_login(client)
    payload={'messages':[{'role':'user','content':'hello integration'}],'model':'deepseek-chat','temperature':0.3,'max_tokens':64,'cache':True}
    a=await client.post('/api/v1/ai/chat',headers=auth(pair),json=payload); assert a.status_code==200 and not a.json()['cache_hit']
    assert a.json()['content'].startswith('[mock]')
    b=await client.post('/api/v1/ai/chat',headers=auth(pair),json=payload); assert b.status_code==200 and b.json()['cache_hit']
    agen=get_session(); db=await agen.__anext__()
    try:
        from app.models.entities import AIRequest
        rows=list((await db.scalars(select(AIRequest))).all()); assert rows and all(x.prompt_encrypted is None for x in rows)
    finally: await agen.aclose()


@pytest.mark.asyncio
async def test_ai_daily_request_quota(client):
    pair=await register_and_login(client,'quota@example.com')
    me=(await client.get('/api/v1/users/me',headers=auth(pair))).json()
    agen=get_session(); db=await agen.__anext__()
    try:
        db.add(AIQuota(user_id=uuid.UUID(me['id']),requests_per_minute=20,requests_per_day=1,tokens_per_day=10000,max_output_tokens=100)); await db.commit()
    finally: await agen.aclose()
    payload={'messages':[{'role':'user','content':'quota test'}],'max_tokens':20,'cache':False}
    assert (await client.post('/api/v1/ai/chat',headers=auth(pair),json=payload)).status_code==200
    r=await client.post('/api/v1/ai/chat',headers=auth(pair),json=payload); assert r.status_code==429 and r.json()['error']['code']=='DAILY_REQUEST_QUOTA'


@pytest.mark.asyncio
async def test_health_readiness_security_headers_and_request_id(client):
    r=await client.get('/health/ready',headers={'X-Request-ID':'integration-req'})
    assert r.status_code==200 and r.json()['database']=='ok' and r.json()['redis']=='ok'
    assert r.headers['X-Request-ID']=='integration-req'
    assert r.headers['X-Content-Type-Options']=='nosniff'


@pytest.mark.asyncio
async def test_invalid_host_rejected(client):
    async with AsyncClient(transport=ASGITransport(app=app),base_url='http://evil.example') as bad:
        r=await bad.get('/health')
        assert r.status_code==400

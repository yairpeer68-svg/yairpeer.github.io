from fastapi.testclient import TestClient
from app.main import app


def client(host="localhost"):
    return TestClient(app,base_url=f"http://{host}")


def test_health_and_request_id_and_headers():
    with client() as c:
        r=c.get('/health',headers={'X-Request-ID':'test-request-123'})
        assert r.status_code==200 and r.json()=={'status':'ok'}
        assert r.headers['X-Request-ID']=='test-request-123'
        assert r.headers['X-Content-Type-Options']=='nosniff'
        assert r.headers['X-Frame-Options']=='DENY'


def test_invalid_request_id_replaced():
    with client() as c:
        r=c.get('/health',headers={'X-Request-ID':'bad id with spaces'})
        assert r.status_code==200 and r.headers['X-Request-ID']!='bad id with spaces'
        assert len(r.headers['X-Request-ID'])==36


def test_version_shape():
    with client() as c:
        data=c.get('/version').json()
        assert set(data)=={'version','git_commit','build_time','environment'}


def test_invalid_host_rejected():
    with client('evil.example') as c:
        r=c.get('/health')
        assert r.status_code==400

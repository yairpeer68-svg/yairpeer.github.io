from app.core.config import Settings
from app.engineering.code_index import CodeIndex
from app.engineering.workspace import Workspace


def settings(tmp_path):
    return Settings(APP_ENV='test', AI_PROVIDER_MODE='mock', JWT_SECRET='x' * 64,
                    ENGINEERING_WORKSPACE_ROOT=str(tmp_path))


def _workspace(tmp_path, key='index'):
    ws = Workspace(settings(tmp_path), 'u', key)
    ws.write_text('src/payment_service.py',
                  'class PaymentProcessor:\n    def refund_transaction(self, transaction_id):\n        return transaction_id\n')
    ws.write_text('src/weather.py', 'def current_temperature(city):\n    return city\n')
    return ws


def test_search_ranks_the_relevant_file_first(tmp_path):
    ws = _workspace(tmp_path)
    assert CodeIndex(ws).rebuild()['files_indexed'] == 2
    hits = CodeIndex(ws).search('refund payment transaction', 5)
    assert hits and hits[0].path == 'src/payment_service.py'


def test_a_second_search_reuses_the_persisted_index(tmp_path, monkeypatch):
    """Staleness is decided by a stat fingerprint, not by re-hashing every byte."""
    ws = _workspace(tmp_path)
    index = CodeIndex(ws)
    index.rebuild()

    calls = {'n': 0}
    original = CodeIndex.rebuild

    def counting_rebuild(self):
        calls['n'] += 1
        return original(self)

    monkeypatch.setattr(CodeIndex, 'rebuild', counting_rebuild)
    CodeIndex(ws).search('refund', 3)
    CodeIndex(ws).search('weather', 3)
    assert calls['n'] == 0


def test_changing_a_file_invalidates_the_index(tmp_path, monkeypatch):
    ws = _workspace(tmp_path)
    CodeIndex(ws).rebuild()
    ws.write_text('src/billing_invoice.py', 'def issue_invoice(customer):\n    return customer\n')

    calls = {'n': 0}
    original = CodeIndex.rebuild

    def counting_rebuild(self):
        calls['n'] += 1
        return original(self)

    monkeypatch.setattr(CodeIndex, 'rebuild', counting_rebuild)
    hits = CodeIndex(ws).search('issue invoice customer', 5)
    assert calls['n'] == 1
    assert any(h.path == 'src/billing_invoice.py' for h in hits)


def test_the_index_directory_is_excluded_from_the_workspace_listing(tmp_path):
    ws = _workspace(tmp_path)
    CodeIndex(ws).rebuild()
    assert all('.ai-platform' not in str(p) for p in ws.files())


def test_search_on_an_empty_query_returns_nothing(tmp_path):
    ws = _workspace(tmp_path)
    CodeIndex(ws).rebuild()
    assert CodeIndex(ws).search('   ', 5) == []

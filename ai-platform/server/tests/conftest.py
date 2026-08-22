import os


def pytest_configure(config):
    config.addinivalue_line("markers", "integration: requires PostgreSQL and Redis")


def integration_enabled() -> bool:
    return os.getenv("RUN_INTEGRATION") == "1"

"""Autonomous engineering runtime.

``run_engineering_run`` is resolved lazily: importing it eagerly pulled SQLAlchemy and
the whole database stack into every consumer of a leaf module such as ``workspace``.
"""
from typing import TYPE_CHECKING

if TYPE_CHECKING:  # pragma: no cover - import-time typing only
    from app.engineering.orchestrator import run_engineering_run

__all__ = ["run_engineering_run"]


def __getattr__(name: str):
    if name == "run_engineering_run":
        from app.engineering.orchestrator import run_engineering_run as value
        return value
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")

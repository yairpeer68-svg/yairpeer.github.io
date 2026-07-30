"""Portable column types.

``pgvector`` only exists on PostgreSQL, but the unit-test suite runs against
SQLite.  :class:`VectorType` therefore compiles to ``vector(n)`` on PostgreSQL
and degrades to a JSON-encoded list elsewhere, so the same models can be used
in both places.
"""

from __future__ import annotations

import json
from typing import Any

from sqlalchemy import JSON, Dialect, String, TypeDecorator
from sqlalchemy.dialects.postgresql import JSONB


class VectorType(TypeDecorator[list[float]]):
    """``vector(dimensions)`` on PostgreSQL, JSON text elsewhere."""

    impl = String
    cache_ok = True

    def __init__(self, dimensions: int) -> None:
        super().__init__()
        self.dimensions = dimensions

    def load_dialect_impl(self, dialect: Dialect) -> Any:
        if dialect.name == "postgresql":
            from pgvector.sqlalchemy import Vector

            return dialect.type_descriptor(Vector(self.dimensions))
        return dialect.type_descriptor(String())

    def process_bind_param(self, value: Any, dialect: Dialect) -> Any:
        if value is None or dialect.name == "postgresql":
            return value
        return json.dumps(list(value))

    def process_result_value(self, value: Any, dialect: Dialect) -> Any:
        if value is None or dialect.name == "postgresql":
            return value
        return json.loads(value)


def json_column() -> Any:
    """``JSONB`` on PostgreSQL, plain ``JSON`` elsewhere."""
    return JSONB().with_variant(JSON(), "sqlite")

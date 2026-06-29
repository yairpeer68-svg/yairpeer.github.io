"""
Ghost Eye v3 - modular information-gathering / reconnaissance toolkit.

A ground-up rewrite of the original single-file tool by Jolanda de Koff
(BullsEye0). Every feature is a self-registering Module; the CLI builds its
menu automatically from the registry.

FOR AUTHORISED SECURITY TESTING ONLY. Only run this against systems you own
or have explicit written permission to assess.
"""

from .core import (Colors, Console, Context, Module, Result, REGISTRY,
                   modules_by_category, register, setup_logging)
from .config import Config

__version__ = "3.7.0"
__all__ = [
    "Colors", "Console", "Context", "Module", "Result", "REGISTRY",
    "modules_by_category", "register", "setup_logging", "Config", "__version__",
]

# Importing the modules package registers every feature.
from . import modules  # noqa: E402,F401

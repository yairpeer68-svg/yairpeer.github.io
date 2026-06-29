#!/usr/bin/env python3
"""Launch the Ghost Eye web dashboard.

    python3 ghost_eye_web.py
    python3 ghost_eye_web.py --open
    python3 ghost_eye_web.py --host 0.0.0.0 --port 9000 --scope scope.txt

Localhost-only by default. Authorised security testing only.
"""
import sys
from ghost_eye.webapp import main

if __name__ == "__main__":
    sys.exit(main())

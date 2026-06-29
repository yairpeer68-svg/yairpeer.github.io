#!/usr/bin/env python3
"""
Ghost Eye v3 launcher.

Usage:
    python3 ghost_eye.py                 # interactive menu
    python3 ghost_eye.py --list          # list all modules
    python3 ghost_eye.py -t example.com -m headers,cert,subs
    python3 ghost_eye.py -t example.com --all -o report.html

FOR AUTHORISED SECURITY TESTING ONLY.
"""
import sys

from ghost_eye.cli import main

if __name__ == "__main__":
    sys.exit(main())

#!/bin/sh
# Ghost Eye container dispatcher: no args (or "web") launches the dashboard;
# any other args are passed straight to the CLI. Reconnaissance/detection only.
set -e
if [ "$#" -eq 0 ] || [ "$1" = "web" ]; then
  [ "$1" = "web" ] && shift
  exec python3 ghost_eye_web.py --host 0.0.0.0 --port "${PORT:-8777}" "$@"
fi
exec python3 ghost_eye.py "$@"

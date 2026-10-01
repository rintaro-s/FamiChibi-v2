#!/bin/sh
# FamiChibi host server: ./run.sh [--port 8000] [--name "わが家のサーバー"]
cd "$(dirname "$0")"
[ -d .venv ] || { python3 -m venv .venv && .venv/bin/pip install -q -r requirements.txt; }
exec .venv/bin/python -m app.main "$@"

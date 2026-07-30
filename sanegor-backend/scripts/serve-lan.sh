#!/usr/bin/env bash
# ============================================================================
# Serve the backend to other devices on the local network.
#
#   ./scripts/serve-lan.sh
#
# Binds 0.0.0.0 instead of localhost and prints the URL to give the phone.
# Detects WSL2 and explains what else has to be done there, because a service
# in WSL2 is reachable from Windows but not from the LAN until Windows is
# told to let it through.
# ============================================================================
set -euo pipefail

PORT="${PORT:-8000}"
cd "$(dirname "${BASH_SOURCE[0]}")/.."

info() { printf '\033[1;34m==>\033[0m %s\n' "$1"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$1"; }

is_wsl() { grep -qiE 'microsoft|wsl' /proc/version 2>/dev/null; }

lan_ip() {
    # The address that owns the default route is the one other devices can
    # reach; `hostname -I` returns docker and virtual interfaces too.
    ip route get 1.1.1.1 2>/dev/null | grep -oP 'src \K[\d.]+' | head -1
}

windows_lan_ip() {
    # From inside WSL2, ask Windows for its own LAN address.
    powershell.exe -NoProfile -Command \
        "(Get-NetIPAddress -AddressFamily IPv4 |
          Where-Object { \$_.InterfaceAlias -notmatch 'Loopback|WSL|vEthernet' -and
                         \$_.IPAddress -notmatch '^169\.254' } |
          Select-Object -First 1 -ExpandProperty IPAddress)" 2>/dev/null |
        tr -d '\r\n'
}

IP="$(lan_ip || true)"

echo
if is_wsl; then
    info "WSL2 detected"
    WSL_IP="$IP"
    WIN_IP="$(windows_lan_ip || true)"

    if [[ -n "${WIN_IP:-}" && "$WSL_IP" == "$WIN_IP" ]]; then
        info "Mirrored networking is on — the phone can reach this directly."
        URL="http://${WIN_IP}:${PORT}"
    else
        warn "WSL2 is on its own NAT network (${WSL_IP:-unknown})."
        warn "The phone talks to Windows, and Windows does not forward into"
        warn "WSL2 by default. Pick one of these, in PowerShell as admin:"
        cat <<EOF

  A. Mirrored networking — simplest, needs Windows 11 + WSL 2.0+.
     Create %UserProfile%\\.wslconfig containing:

         [wsl2]
         networkingMode=mirrored

     then:  wsl --shutdown     (and reopen this shell)

  B. Port forward — works on any version, but the WSL2 IP changes on
     every restart so this must be redone:

         netsh interface portproxy add v4tov4 \\
             listenport=${PORT} listenaddress=0.0.0.0 \\
             connectport=${PORT} connectaddress=${WSL_IP:-<wsl-ip>}

  In both cases open the firewall once:

         New-NetFirewallRule -DisplayName "Sanegor ${PORT}" \\
             -Direction Inbound -LocalPort ${PORT} -Protocol TCP -Action Allow

EOF
        URL="http://${WIN_IP:-<windows-ip>}:${PORT}"
    fi
else
    URL="http://${IP:-<your-ip>}:${PORT}"
fi

info "Build the app against this address:"
echo
echo "    ./scripts/run-local.sh run    # in sanegor-app, with API_BASE_URL set to:"
echo "    ${URL}"
echo
warn "Reachable only while the phone is on the same WiFi as this machine."
echo

exec uvicorn app.main:app --host 0.0.0.0 --port "$PORT" --reload

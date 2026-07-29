#!/bin/bash
# WireGuard VPN Server Setup for GameBoost
# Run on Ubuntu 22.04: sudo bash wireguard-setup.sh
# Sets up a WireGuard server optimized for gaming (low latency, BBR)

set -euo pipefail

WG_PORT=51820
WG_INTERFACE="wg0"
WG_SUBNET="10.0.0.0/24"
WG_ADDRESS="10.0.0.1/24"
CLIENT_IP="10.0.0.2/32"

echo "=== GameBoost WireGuard Server Setup ==="
echo "Port: $WG_PORT | Subnet: $WG_SUBNET"

# Install WireGuard
apt-get update -qq
apt-get install -y wireguard wireguard-tools qrencode

# Enable IP forwarding
cat >> /etc/sysctl.conf << 'EOF'
net.ipv4.ip_forward=1
net.ipv6.conf.all.forwarding=1
# BBR congestion control (reduces bufferbloat)
net.core.default_qdisc=fq
net.ipv4.tcp_congestion_control=bbr
# Gaming optimizations
net.core.rmem_max=16777216
net.core.wmem_max=16777216
net.ipv4.tcp_rmem=4096 87380 16777216
net.ipv4.tcp_wmem=4096 65536 16777216
EOF
sysctl -p > /dev/null

# Generate server keys
SERVER_PRIVATE=$(wg genkey)
SERVER_PUBLIC=$(echo "$SERVER_PRIVATE" | wg pubkey)
CLIENT_PRIVATE=$(wg genkey)
CLIENT_PUBLIC=$(echo "$CLIENT_PRIVATE" | wg pubkey)
PRESHARED=$(wg genpsk)

# Get external IP
EXTERNAL_IP=$(curl -s https://ifconfig.me || hostname -I | awk '{print $1}')

# WireGuard server config
cat > /etc/wireguard/${WG_INTERFACE}.conf << EOF
[Interface]
Address = $WG_ADDRESS
ListenPort = $WG_PORT
PrivateKey = $SERVER_PRIVATE
PostUp   = iptables -A FORWARD -i %i -j ACCEPT; iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE
PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -t nat -D POSTROUTING -o eth0 -j MASQUERADE
SaveConfig = false

[Peer]
PublicKey  = $CLIENT_PUBLIC
PresharedKey = $PRESHARED
AllowedIPs = $CLIENT_IP
EOF

chmod 600 /etc/wireguard/${WG_INTERFACE}.conf

# Enable + start WireGuard
systemctl enable wg-quick@${WG_INTERFACE}
systemctl start wg-quick@${WG_INTERFACE}

# UFW firewall rules
if command -v ufw &>/dev/null; then
    ufw allow ${WG_PORT}/udp comment "WireGuard GameBoost"
    ufw allow OpenSSH
fi

# Client config (for Android app)
CLIENT_CONFIG="[Interface]
PrivateKey = $CLIENT_PRIVATE
Address = $CLIENT_IP
DNS = 1.1.1.1, 1.0.0.1
MTU = 1420

[Peer]
PublicKey = $SERVER_PUBLIC
PresharedKey = $PRESHARED
Endpoint = $EXTERNAL_IP:$WG_PORT
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25"

echo ""
echo "=== Client Config for GameBoost App ==="
echo "$CLIENT_CONFIG"
echo ""
echo "=== QR Code (scan with GameBoost app) ==="
echo "$CLIENT_CONFIG" | qrencode -t ansiutf8

# Save client config
echo "$CLIENT_CONFIG" > /root/gameBoost-client.conf
echo ""
echo "✅ Setup complete!"
echo "Server public key: $SERVER_PUBLIC"
echo "Client config saved to: /root/gameBoost-client.conf"
echo "WireGuard status: $(systemctl is-active wg-quick@${WG_INTERFACE})"

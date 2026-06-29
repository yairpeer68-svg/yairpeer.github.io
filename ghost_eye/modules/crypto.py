"""Crypto & blockchain recon modules (features #51-#55). Detection only."""

from __future__ import annotations

import re
import socket
from concurrent.futures import ThreadPoolExecutor
from typing import Dict

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


@register
class Web3RpcDetect(Module):
    id, name, category = "web3rpc", "Web3 JSON-RPC endpoint detection", "Crypto"
    target_kind = "url"

    _PATHS = ["/", "/rpc", "/api", "/jsonrpc", "/web3",
              "/eth", "/bsc", "/polygon", "/solana"]
    _PORTS = [8545, 8546, 8547, 8551, 8899, 9545, 18545]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        bare = host.split(":")[0]
        findings = {}

        rpc_payload = '{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":1}'
        eth_block = '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":2}'
        net_version = '{"jsonrpc":"2.0","method":"net_version","params":[],"id":3}'

        def probe_http(item):
            port, path = item
            url = f"http://{bare}:{port}{path}"
            try:
                r = ctx.session.post(url, data=rpc_payload,
                                     headers={"Content-Type": "application/json"},
                                     timeout=min(ctx.timeout, 5))
                if r.status_code == 200 and "jsonrpc" in r.text[:500]:
                    info = {"url": url, "status": 200}
                    try:
                        j = r.json()
                        if "result" in j:
                            info["client"] = str(j["result"])[:80]
                    except Exception:
                        pass
                    # try eth_blockNumber
                    try:
                        r2 = ctx.session.post(url, data=eth_block,
                                              headers={"Content-Type": "application/json"},
                                              timeout=min(ctx.timeout, 3))
                        j2 = r2.json()
                        if "result" in j2:
                            info["block_number"] = j2["result"]
                    except Exception:
                        pass
                    # try net_version
                    try:
                        r3 = ctx.session.post(url, data=net_version,
                                              headers={"Content-Type": "application/json"},
                                              timeout=min(ctx.timeout, 3))
                        j3 = r3.json()
                        if "result" in j3:
                            info["network_id"] = j3["result"]
                    except Exception:
                        pass
                    return info
            except Exception:
                pass
            return None

        items = [(p, path) for p in self._PORTS for path in self._PATHS]
        with ThreadPoolExecutor(max_workers=min(ctx.threads, 6)) as ex:
            for result in ex.map(probe_http, items[:40]):
                if result:
                    key = result["url"][:60]
                    findings[key] = result

        # also check standard base URL
        for path in self._PATHS:
            url = base + path
            try:
                r = ctx.session.post(url, data=rpc_payload,
                                     headers={"Content-Type": "application/json"},
                                     timeout=min(ctx.timeout, 5))
                if r.status_code == 200 and "jsonrpc" in r.text[:500]:
                    info = {"url": url, "status": 200}
                    try:
                        j = r.json()
                        if "result" in j:
                            info["client"] = str(j["result"])[:80]
                    except Exception:
                        pass
                    findings[url[:60]] = info
            except Exception:
                continue

        risk = "informational"
        if findings:
            risk = "CRITICAL"

        return self.ok(host, {
            "web3_rpc": findings or "no RPC endpoints found",
            "risk": risk,
            "note": "exposed RPC allows blockchain interaction and wallet enumeration"
        })


@register
class CryptoAddrScan(Module):
    id, name, category = "cryptoaddr", "Cryptocurrency address exposure", "Crypto"
    target_kind = "url"

    _PATTERNS = {
        "bitcoin": re.compile(r'\b(?:1[a-km-zA-HJ-NP-Z1-9]{25,34}|3[a-km-zA-HJ-NP-Z1-9]{25,34}|bc1[a-zA-HJ-NP-Z0-9]{25,90})\b'),
        "ethereum": re.compile(r'\b0x[a-fA-F0-9]{40}\b'),
        "monero": re.compile(r'\b4[0-9AB][1-9A-HJ-NP-Za-km-z]{93}\b'),
        "litecoin": re.compile(r'\b[LM][a-km-zA-HJ-NP-Z1-9]{26,33}\b'),
        "dogecoin": re.compile(r'\bD[5-9A-HJ-NP-U][1-9A-HJ-NP-Za-km-z]{32}\b'),
        "solana": re.compile(r'\b[1-9A-HJ-NP-Za-km-z]{32,44}\b'),
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        pages = [base, base + "/donate", base + "/about",
                 base + "/contact", base + "/support", base + "/payment"]

        for url in pages:
            try:
                r = ctx.session.get(url, timeout=ctx.timeout)
                if r.status_code != 200:
                    continue
                body = r.text[:50_000]
                path = url.replace(base, "") or "/"
                for coin, pattern in self._PATTERNS.items():
                    if coin == "solana":
                        continue
                    matches = pattern.findall(body)
                    if matches:
                        findings.setdefault(coin, []).append({
                            "page": path,
                            "addresses": list(set(matches))[:5],
                        })
            except Exception:
                continue

        return self.ok(host, {
            "crypto_addresses": findings or "none found",
            "risk": "LOW" if findings else "informational",
        })


@register
class SmartContractScan(Module):
    id, name, category = "smartcontract", "Smart contract / DApp detection", "Crypto"
    target_kind = "url"

    _WEB3_INDICATORS = [
        "web3.js", "ethers.js", "web3modal", "wagmi", "rainbowkit",
        "connectwallet", "metamask", "walletconnect", "window.ethereum",
        "web3.eth", "ethers.providers", "contract.methods",
        "solidity", "0x", "abi", "bytecode",
    ]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:150_000].lower()

            detected = [ind for ind in self._WEB3_INDICATORS if ind in body]
            if detected:
                findings["web3_indicators"] = detected

            contracts = re.findall(r'0x[a-fA-F0-9]{40}', body)
            if contracts:
                unique = list(set(contracts))[:10]
                findings["contract_addresses"] = unique

            abis = re.findall(r'"abi"\s*:\s*\[', body)
            if abis:
                findings["abi_definitions"] = len(abis)

            chains = []
            chain_map = {
                "mainnet": "Ethereum Mainnet", "goerli": "Goerli Testnet",
                "sepolia": "Sepolia Testnet", "polygon": "Polygon",
                "arbitrum": "Arbitrum", "optimism": "Optimism",
                "avalanche": "Avalanche", "bsc": "BSC",
                "fantom": "Fantom", "base": "Base",
            }
            for chain_id, chain_name in chain_map.items():
                if chain_id in body:
                    chains.append(chain_name)
            if chains:
                findings["chains"] = chains

        except Exception as e:
            return self.fail(host, str(e)[:80])

        return self.ok(host, {
            "dapp": findings or "no Web3/DApp indicators found",
            "risk": "LOW" if findings else "informational",
        })


@register
class IpfsGateway(Module):
    id, name, category = "ipfsgw", "IPFS gateway & pinning detection", "Crypto"
    target_kind = "url"

    _GATEWAY_PATHS = ["/ipfs/", "/ipns/", "/api/v0/version",
                      "/api/v0/id", "/api/v0/swarm/peers",
                      "/api/v0/pin/ls"]
    _PORTS = [5001, 8080, 4001]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        bare = host.split(":")[0]
        findings = {}

        # check IPFS API
        for port in self._PORTS:
            api_url = f"http://{bare}:{port}/api/v0/version"
            try:
                r = ctx.session.post(api_url, timeout=min(ctx.timeout, 5))
                if r.status_code == 200 and "Version" in r.text:
                    info = {"port": port, "api_exposed": True, "risk": "CRITICAL"}
                    try:
                        info["version"] = r.json().get("Version", "")
                    except Exception:
                        pass
                    findings[f"ipfs_api:{port}"] = info
                    # check swarm peers
                    try:
                        r2 = ctx.session.post(
                            f"http://{bare}:{port}/api/v0/swarm/peers",
                            timeout=min(ctx.timeout, 3))
                        if r2.status_code == 200:
                            findings[f"ipfs_api:{port}"]["swarm_exposed"] = True
                    except Exception:
                        pass
            except Exception:
                continue

        # check gateway
        test_cid = "QmT78zSuBmuS4z925WZfrqQ1qHaJ56DQaTfyMUF7F8ff5o"
        for path_prefix in ["/ipfs/", f"http://{bare}:8080/ipfs/"]:
            url = path_prefix + test_cid if path_prefix.startswith("http") else base + path_prefix + test_cid
            try:
                r = ctx.session.get(url, timeout=min(ctx.timeout, 5),
                                    allow_redirects=False)
                if r.status_code in (200, 301, 302):
                    findings["gateway"] = {
                        "accessible": True,
                        "url": url[:80],
                    }
            except Exception:
                continue

        # check page for IPFS references
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:50_000].lower()
            if "ipfs" in body or "/ipfs/" in body or "ipns" in body:
                findings["page_references"] = True
        except Exception:
            pass

        risk = "informational"
        if any(f.get("api_exposed") for f in findings.values()
               if isinstance(f, dict)):
            risk = "CRITICAL"
        elif findings:
            risk = "LOW"

        return self.ok(host, {
            "ipfs": findings or "no IPFS detected",
            "risk": risk,
        })


@register
class EnsScan(Module):
    id, name, category = "ensscan", "ENS / blockchain domain detection", "Crypto"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:100_000]

            ens_names = re.findall(r'\b[\w-]+\.eth\b', body)
            if ens_names:
                findings["ens_names"] = list(set(ens_names))[:15]

            unstoppable = re.findall(
                r'\b[\w-]+\.(?:crypto|nft|x|wallet|bitcoin|dao|888|zil|blockchain)\b',
                body)
            if unstoppable:
                findings["unstoppable_domains"] = list(set(unstoppable))[:10]

            handshake = re.findall(r'\b[\w-]+\.(?:hns|forever)\b', body)
            if handshake:
                findings["handshake_domains"] = list(set(handshake))[:10]

            if any(kw in body.lower() for kw in
                   ["ens.", "ethereum name service", "unstoppabledomains"]):
                findings["blockchain_dns_references"] = True

        except Exception as e:
            return self.fail(host, str(e)[:80])

        return self.ok(host, {
            "blockchain_domains": findings or "none found",
            "risk": "LOW" if findings else "informational",
        })

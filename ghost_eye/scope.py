"""Scope guard (new). Load a list of authorised hosts / CIDRs and refuse any
target outside it. Helps keep an engagement inside its rules-of-engagement.

A scope file has one entry per line (blank lines and #comments ignored):

    example.com           # the domain and any subdomain (*.example.com)
    api.example.com       # an exact host
    192.0.2.0/24          # a CIDR range
    198.51.100.7          # a single IP
"""

from __future__ import annotations

import ipaddress
import os
import re
from typing import List, Optional, Tuple
from urllib.parse import urlparse


def _host_of(target: str) -> str:
    t = target.strip()
    if "://" in t:
        t = urlparse(t).hostname or t
    return t.split("/")[0].split(":")[0].strip().lower().rstrip(".")


class Scope:
    def __init__(self, domains=None, networks=None, ips=None) -> None:
        self.domains = set(domains or [])
        self.networks = list(networks or [])
        self.ips = set(ips or [])

    @property
    def empty(self) -> bool:
        return not (self.domains or self.networks or self.ips)

    @classmethod
    def from_lines(cls, lines: List[str]) -> "Scope":
        domains, networks, ips = set(), [], set()
        for raw in lines:
            entry = raw.split("#", 1)[0].strip().lower()
            if not entry:
                continue
            if "/" in entry:
                try:
                    networks.append(ipaddress.ip_network(entry, strict=False))
                    continue
                except ValueError:
                    pass
            try:
                ipaddress.ip_address(entry)
                ips.add(entry)
                continue
            except ValueError:
                pass
            domains.add(entry.lstrip("*.").rstrip("."))
        return cls(domains, networks, ips)

    @classmethod
    def from_file(cls, path: str) -> "Scope":
        if not path or not os.path.exists(path):
            return cls()
        with open(path, encoding="utf-8") as fh:
            return cls.from_lines(fh.readlines())

    def allows(self, target: str) -> Tuple[bool, str]:
        if self.empty:
            return True, "no scope set"
        host = _host_of(target)
        try:
            ip = ipaddress.ip_address(host)
            if host in self.ips:
                return True, "IP in scope"
            for net in self.networks:
                if ip in net:
                    return True, f"IP within {net}"
            return False, f"{host} is outside the IP scope"
        except ValueError:
            pass
        for dom in self.domains:
            if host == dom or host.endswith("." + dom):
                return True, f"matches {dom}"
        return False, f"{host} is outside the domain scope"

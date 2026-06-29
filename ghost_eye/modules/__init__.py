"""Importing this package imports every module file, which runs each
@register decorator and fills core.REGISTRY. Adding a feature = drop a Module
subclass into one of these files (or add a new file to the list below)."""

from . import (  # noqa: F401
    dns_recon,
    whois_recon,
    subdomains,
    network,
    tls,
    web,
    osint,
    intel,
    exposure,
    # --- expansion pack ---
    email_adv,
    tls_adv,
    web_adv,
    cloud,
    network_adv,
    osint_adv,
    passive,
    fingerprint,
    # --- v3.2 additions ---
    portscan,
    doh,
    cve,
    # --- v3.3: free no-key intel (replaces paid Shodan/Censys) ---
    freeintel,
    # --- v3.5: 80 new features ---
    dns_adv,
    tls_v2,
    web_v2,
    network_v2,
    email_v2,
    cloud_v2,
    osint_v2,
    # --- v3.6: AI/LLM recon ---
    ai_recon,
)

__all__ = [
    "dns_recon", "whois_recon", "subdomains", "network", "tls",
    "web", "osint", "intel", "exposure",
    "email_adv", "tls_adv", "web_adv", "cloud", "network_adv",
    "osint_adv", "passive", "fingerprint",
    "portscan", "doh", "cve", "freeintel",
    "dns_adv", "tls_v2", "web_v2", "network_v2",
    "email_v2", "cloud_v2", "osint_v2",
    "ai_recon",
]

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
    # --- v3.7: 80 more features ---
    api_security,
    auth_session,
    privacy,
    supply_chain,
    iot,
    web_v3,
    crypto,
    osint_v3,
    cloud_v3,
    network_v3,
    # --- v3.8: exploit / zero-day intelligence ---
    exploit_intel,
    # --- v3.8: visual recon ---
    screenshot,
    # --- v3.8: 13 new-capability modules ---
    web_v4,
    dns_v4,
    email_v3,
    tls_v3,
    network_v4,
    ai_v2,
    mobile,
    osint_sources,
    newscan_wave,
    osint_power,
    osint_freesources,
    # --- v3.85: AI/LLM provider account recon + mobile association files ---
    ai_providers,
    mobile_v2,
    # --- v3.86: data-driven OSINT at scale (username/email registries) ---
    osint_scale,
    # --- v3.91: CDN/WAF edge filtering ---
    netfilter,
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
    "api_security", "auth_session", "privacy", "supply_chain",
    "iot", "web_v3", "crypto", "osint_v3", "cloud_v3", "network_v3",
    "exploit_intel", "screenshot",
    "web_v4", "dns_v4", "email_v3", "tls_v3", "network_v4", "ai_v2", "mobile",
    "osint_sources", "newscan_wave", "osint_power", "osint_freesources",
    "ai_providers", "mobile_v2", "osint_scale", "netfilter",
]

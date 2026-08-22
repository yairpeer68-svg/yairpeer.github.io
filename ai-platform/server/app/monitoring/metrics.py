from prometheus_client import Counter, Histogram

HTTP_REQUESTS = Counter("http_requests_total", "HTTP requests", ["method", "path", "status"])
HTTP_LATENCY = Histogram("http_request_duration_seconds", "HTTP latency", ["method", "path"])
HTTP_ERRORS = Counter("http_errors_total", "HTTP errors", ["path", "status"])
AI_REQUESTS = Counter("ai_requests_total", "AI requests", ["status", "cache_hit"])
AI_LATENCY = Histogram("ai_request_duration_seconds", "AI latency")
AI_FAILURES = Counter("ai_failures_total", "AI failures", ["code"])
CACHE_HITS = Counter("ai_cache_hits_total", "AI cache hits")
RATE_LIMIT_HITS = Counter("rate_limit_hits_total", "Rate limit hits", ["scope"])

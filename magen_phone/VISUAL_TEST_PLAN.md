# Visual Shield device test plan

After building/installing v4.1:

1. Confirm Accessibility and Magen VPN are active.
2. Open Google app and scroll a safe news/search page for 30s — it should remain usable.
3. Open a results page containing lingerie/suggestive product cards — Strict mode should show the opaque Magen curtain and navigate away after a local verdict.
4. Repeat in Telegram/Instagram/Chrome with safe content and with controlled test content.
5. On VPS run `sudo magenctl events --limit 50` and confirm `VISUAL_MODEL_READY` and `VISUAL_BLOCK_LOCAL` metadata.
6. Confirm event details contain only package/label/scores/tile index — never image bytes.
7. Run `sudo magenctl visual balanced` and repeat to compare false positives; restore `sudo magenctl visual strict` for the requested high-sensitivity mode.
8. If a secure/protected window cannot be captured, expect `VISUAL_CAPTURE_UNAVAILABLE`; existing URL/text/app filtering remains active.

Do not calibrate accuracy from one or two screenshots. Build a labeled test set of safe + lingerie + nudity + explicit + drawings and measure recall/false-positive rate before claiming a numeric accuracy target.

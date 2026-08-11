"""Email delivery for reports and alerts.

The webhook sink already existed; this is the other half, because most teams
read email and comparatively few will stand up a webhook receiver just to find
out that their TLS certificate expires in nine days.

Three deliberate constraints:

* **TLS by default.** ``starttls`` is on unless you turn it off, and turning it
  off is only sane for an SMTP relay on localhost. A report lists your own
  weaknesses — it is exactly the message you do not want on the wire in clear.
* **The password is write-only.** ``config()`` never returns it, and the
  ``__repr__`` does not include it, so it cannot be echoed into a dashboard,
  a log line or an audit entry by accident.
* **Nothing sends itself.** There is no implicit "email on every scan": a send
  happens because an alert rule fired or an operator pressed a button. A tool
  that mails out findings unprompted is a tool that eventually mails them to
  the wrong list.
"""

from __future__ import annotations

import re
import smtplib
import ssl
from email.message import EmailMessage
from email.utils import formataddr, parseaddr
from typing import Any, Dict, List, Optional, Sequence

DEFAULT_PORT = 587          # submission-with-STARTTLS; the common case
IMPLICIT_TLS_PORT = 465     # SMTPS, TLS from the first byte
MAX_BODY = 400_000          # a report that will not fit belongs in an attachment

_ADDR = re.compile(r"^[^@\s,;]+@[A-Za-z0-9]([A-Za-z0-9.\-]*[A-Za-z0-9])?"
                   r"\.[A-Za-z]{2,}$")


def valid_address(addr: str) -> bool:
    """Is this a plausible single email address?

    Not RFC 5322 — that grammar accepts things no real mail server wants. This
    rejects the mistakes that actually happen: an empty field, a missing @, a
    bare hostname, and a comma-separated list smuggled into a single field.
    """
    raw = str(addr or "").strip()
    name, email = parseaddr(raw)
    if not email or not _ADDR.match(email):
        return False
    # parseaddr silently repairs "a@ example.com" into "a@example.com". Without
    # a display name there is nothing to repair, so a mismatch means the input
    # contained whitespace or quoting we should not be guessing at.
    if not name and email != raw:
        return False
    return True


def split_recipients(value: Any) -> List[str]:
    """Accept a list, or a string separated by commas/semicolons/whitespace."""
    if isinstance(value, (list, tuple, set)):
        parts: List[str] = [str(v) for v in value]
    else:
        parts = re.split(r"[,;\s]+", str(value or ""))
    return [p.strip() for p in parts if p.strip()]


class MailError(RuntimeError):
    """A send failed. The message is safe to show a user: it never contains
    the password, because we never put it in one."""


class Mailer:
    """A configured SMTP sender.

    ``sender`` is the SMTP client factory, injected so tests can drive the
    whole path — including the failure branches — without a mail server.
    """

    def __init__(self, host: str = "", port: int = DEFAULT_PORT,
                 username: str = "", password: str = "",
                 sender: str = "", use_tls: bool = True,
                 timeout: float = 20.0, client=None) -> None:
        self.host = str(host or "").strip()
        # An explicit 0 or 70000 must survive to problems() and be reported.
        # Coercing it to the default here is how a typo'd port turns into a
        # connection to the wrong service with no explanation.
        try:
            self.port = DEFAULT_PORT if port in (None, "") else int(port)
        except (TypeError, ValueError):
            self.port = -1
        self.username = str(username or "").strip()
        self._password = str(password or "")
        self.sender = str(sender or username or "").strip()
        self.use_tls = bool(use_tls)
        self.timeout = float(timeout)
        self._client = client

    # ---- introspection ---------------------------------------------------- #
    def __repr__(self) -> str:                    # no password, ever
        return (f"Mailer(host={self.host!r}, port={self.port}, "
                f"username={self.username!r}, use_tls={self.use_tls})")

    @property
    def configured(self) -> bool:
        return bool(self.host and self.sender)

    def config(self) -> Dict[str, Any]:
        """What the dashboard may display. The password is represented by
        whether it is set, never by its value."""
        return {
            "host": self.host,
            "port": self.port,
            "username": self.username,
            "sender": self.sender,
            "use_tls": self.use_tls,
            "password_set": bool(self._password),
            "configured": self.configured,
        }

    def problems(self) -> List[str]:
        """Everything wrong with this configuration, in one pass — so the user
        fixes all of it at once instead of one round-trip per mistake."""
        out: List[str] = []
        if not self.host:
            out.append("SMTP host is not set")
        if not (0 < self.port < 65536):
            out.append(f"port {self.port} is out of range")
        if not self.sender:
            out.append("sender address is not set")
        elif not valid_address(self.sender):
            out.append(f"sender {self.sender!r} is not a valid address")
        if self.username and not self._password:
            out.append("a username is set but no password")
        if not self.use_tls and self.host not in ("localhost", "127.0.0.1", "::1"):
            out.append("TLS is disabled for a non-local host — reports would "
                       "cross the network in clear text")
        return out

    # ---- sending ---------------------------------------------------------- #
    def build(self, to: Sequence[str], subject: str, body: str,
              html: str = "", attachment: Optional[tuple] = None) -> EmailMessage:
        """Compose the message. Separate from sending so a caller — or a test —
        can inspect exactly what would go out before anything goes out."""
        msg = EmailMessage()
        msg["From"] = formataddr(("Ghost Eye", self.sender)) if self.sender else ""
        msg["To"] = ", ".join(to)
        msg["Subject"] = str(subject or "Ghost Eye report")[:200]
        msg.set_content(str(body or "")[:MAX_BODY])
        if html:
            msg.add_alternative(str(html)[:MAX_BODY], subtype="html")
        if attachment:
            name, data = attachment
            msg.add_attachment(data if isinstance(data, bytes) else str(data).encode(),
                               maintype="application", subtype="octet-stream",
                               filename=str(name)[:120])
        return msg

    def send(self, to: Any, subject: str, body: str, html: str = "",
             attachment: Optional[tuple] = None) -> Dict[str, Any]:
        """Deliver one message. Raises MailError with a readable reason."""
        problems = self.problems()
        if problems:
            raise MailError("; ".join(problems))
        recipients = [r for r in split_recipients(to)]
        if not recipients:
            raise MailError("no recipients")
        bad = [r for r in recipients if not valid_address(r)]
        if bad:
            raise MailError(f"invalid recipient(s): {', '.join(bad)}")

        msg = self.build(recipients, subject, body, html, attachment)
        try:
            with self._connect() as smtp:
                if self.use_tls and self.port != IMPLICIT_TLS_PORT:
                    smtp.starttls(context=ssl.create_default_context())
                if self.username:
                    smtp.login(self.username, self._password)
                smtp.send_message(msg)
        except MailError:
            raise
        except smtplib.SMTPAuthenticationError as exc:
            raise MailError("SMTP rejected the credentials "
                            f"({exc.smtp_code})") from exc
        except Exception as exc:  # noqa: BLE001 - one readable error for the UI
            raise MailError(f"{type(exc).__name__}: {exc}") from exc
        return {"sent": True, "recipients": recipients,
                "subject": msg["Subject"], "bytes": len(bytes(msg))}

    def _connect(self):
        if self._client is not None:
            return self._client(self.host, self.port, timeout=self.timeout)
        if self.use_tls and self.port == IMPLICIT_TLS_PORT:
            return smtplib.SMTP_SSL(self.host, self.port, timeout=self.timeout,
                                    context=ssl.create_default_context())
        return smtplib.SMTP(self.host, self.port, timeout=self.timeout)


def report_email(target: str, risk: Dict[str, Any],
                 findings: Sequence[Dict[str, Any]], limit: int = 25) -> tuple:
    """Render (subject, text) for a scan. Plain text on purpose: it renders
    identically in every client, and a report is a list, not a brochure."""
    level = str((risk or {}).get("risk_level") or "unknown")
    score = (risk or {}).get("risk_score")
    subject = f"Ghost Eye: {target} — {level}" + (f" ({score})" if score is not None else "")
    lines = [f"Target: {target}", f"Risk: {level}"
             + (f"  score {score}" if score is not None else ""),
             f"Findings: {len(findings)}", ""]
    for f in list(findings)[:limit]:
        sev = str(f.get("severity") or f.get("level") or "").upper()
        title = str(f.get("title") or f.get("field") or f.get("issue") or "finding")
        detail = str(f.get("value") or f.get("detail") or "")[:160]
        lines.append(f"  [{sev or '-'}] {title}" + (f" — {detail}" if detail else ""))
    if len(findings) > limit:
        lines.append(f"  … and {len(findings) - limit} more")
    lines += ["", "Reconnaissance only — nothing here was exploited.",
              "Sent by Ghost Eye."]
    return subject, "\n".join(lines)

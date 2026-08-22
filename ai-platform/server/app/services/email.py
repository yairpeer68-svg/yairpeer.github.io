import smtplib
import ssl
from email.message import EmailMessage

from app.core.config import Settings


def send_email_sync(settings: Settings, recipient: str, subject: str, text: str) -> str:
    if not settings.email_configured:
        return "not configured"
    msg=EmailMessage(); msg["From"]=settings.SMTP_FROM; msg["To"]=recipient; msg["Subject"]=subject; msg.set_content(text)
    with smtplib.SMTP(settings.SMTP_HOST,settings.SMTP_PORT,timeout=20) as smtp:
        if settings.SMTP_STARTTLS:
            smtp.starttls(context=ssl.create_default_context())
        if settings.SMTP_USERNAME:
            smtp.login(settings.SMTP_USERNAME,settings.SMTP_PASSWORD)
        smtp.send_message(msg)
    return "sent"

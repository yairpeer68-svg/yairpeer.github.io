// צל דיגיטלי — monitor-sweep (Supabase Edge Function, Deno)
//
// פונקציה מתוזמנת: רצה פעם ביום, עוברת על כל המיילים בניטור, בודקת אותם
// מול HIBP, ואם התגלתה דליפה חדשה — שולחת התראת מייל למשתמש דרך Resend.
//
// סודות נדרשים (Edge Functions → Secrets):
//   HIBP_API_KEY   — מפתח Have I Been Pwned (אותו סוד של scan)
//   RESEND_API_KEY — מפתח מ-resend.com לשליחת המיילים
//   ALERT_FROM     — כתובת השולח, למשל: "צל דיגיטלי <alerts@yourdomain.com>"
//                    (עד שיש דומיין מאומת אפשר להשתמש ב-"onboarding@resend.dev")
//   CRON_SECRET    — מחרוזת סודית להגנה על ההפעלה (נשלחת ב-header מה-cron)

import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const HIBP_KEY = Deno.env.get("HIBP_API_KEY") ?? "";
const RESEND_KEY = Deno.env.get("RESEND_API_KEY") ?? "";
const ALERT_FROM = Deno.env.get("ALERT_FROM") ?? "צל דיגיטלי <onboarding@resend.dev>";
const CRON_SECRET = Deno.env.get("CRON_SECRET") ?? "";

async function breachCount(email: string): Promise<number> {
  if (!HIBP_KEY) return -1;
  const res = await fetch(
    `https://haveibeenpwned.com/api/v3/breachedaccount/${encodeURIComponent(email)}?truncateResponse=true`,
    { headers: { "hibp-api-key": HIBP_KEY, "user-agent": "TzelDigitali" } },
  );
  if (res.status === 404) return 0;
  if (!res.ok) return -1;
  const d = await res.json();
  return Array.isArray(d) ? d.length : 0;
}

async function sendAlert(to: string, total: number, added: number) {
  if (!RESEND_KEY) return false;
  const res = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: { Authorization: `Bearer ${RESEND_KEY}`, "content-type": "application/json" },
    body: JSON.stringify({
      from: ALERT_FROM,
      to,
      subject: "🛡️ צל דיגיטלי — זוהתה דליפה חדשה של המייל שלך",
      html: `<div dir="rtl" style="font-family:Arial,'Segoe UI',sans-serif;font-size:16px;line-height:1.75;color:#0c1b2e;max-width:520px;margin:0 auto">
        <div style="background:#071324;color:#27e0c0;padding:18px 20px;border-radius:14px;text-align:center;font-size:20px;font-weight:800">🕵️ צל דיגיטלי — התראת ניטור</div>
        <p style="margin-top:18px">המייל שלך <b>${to}</b> הופיע ב-${added === 1 ? "דליפת מידע חדשה" : added + " דליפות חדשות"}.</p>
        <p>סך הכול הוא נמצא כעת ב-<b>${total}</b> דליפות ידועות.</p>
        <p style="font-weight:700;margin-top:16px">מה כדאי לעשות עכשיו:</p>
        <ol>
          <li>החלף את הסיסמה בכל אתר שבו השתמשת באותה סיסמה.</li>
          <li>הפעל אימות דו-שלבי (2FA) בחשבונות הקריטיים.</li>
          <li>פתח את צל דיגיטלי לסריקה מלאה וצעדי הגנה מפורטים.</li>
        </ol>
        <p style="color:#8098ad;font-size:13px;margin-top:20px">קיבלת מייל זה כי הפעלת ניטור בצל דיגיטלי. אפשר לכבות ניטור באפליקציה בכל רגע.</p>
      </div>`,
    }),
  });
  return res.ok;
}

Deno.serve(async (req) => {
  // הגנה: רק הפעלה עם הסוד הנכון (מה-cron) מורשית
  if (CRON_SECRET && req.headers.get("x-cron-secret") !== CRON_SECRET) {
    return new Response(JSON.stringify({ error: "forbidden" }), { status: 403 });
  }

  const admin = createClient(SUPABASE_URL, SERVICE_KEY);
  const { data: monitors, error } = await admin
    .from("monitors").select("*").eq("active", true);
  if (error) return json({ error: error.message }, 500);

  let checked = 0, alerts = 0;
  for (const m of monitors ?? []) {
    if (!m.email) continue;
    const count = await breachCount(m.email);
    checked++;
    if (count >= 0) {
      const prev = m.last_breach_count ?? 0;
      if (count > prev) {
        const ok = await sendAlert(m.email, count, count - prev);
        if (ok) alerts++;
      }
      if (count !== prev) {
        await admin.from("monitors")
          .update({ last_breach_count: count, last_checked: new Date().toISOString() })
          .eq("id", m.id);
      }
    }
    // כיבוד מגבלת הקצב של HIBP (10 בקשות בדקה בתוכנית Core 1 → בקשה כל ~1.7 שניות)
    await new Promise((r) => setTimeout(r, 1700));
  }

  return json({ ok: true, checked, alerts }, 200);
});

function json(body: unknown, status: number) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

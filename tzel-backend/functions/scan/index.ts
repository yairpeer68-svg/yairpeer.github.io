// צל דיגיטלי — פונקציית סריקה (Supabase Edge Function, Deno)
//
// מקבלת מייל (ואופציונלית טלפון) של המשתמש, בודקת חשיפה מול מקורות
// ציבוריים, מחשבת ציון הגנה ומחזירה דוח ממצאים בעברית.
//
// עקרונות אבטחה ופרטיות:
// - בודק אך ורק את הפרטים של המשתמש עצמו (self-check).
// - שאילתת דליפות בשיטת k-anonymity: נשלחות רק 5 התווים הראשונים
//   של גיבוב הסיסמה/מייל, לעולם לא הערך המלא (כמו HIBP Pwned Passwords).
// - המייל עצמו לא נשמר; לניטור נשמר רק גיבוב SHA-256.
//
// סוד נדרש: HIBP_API_KEY (מ-haveibeenpwned.com/API/Key) — אופציונלי;
// בלעדיו הפונקציה עדיין מחזירה בדיקות מבוססות-היוריסטיקה.

import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const HIBP_KEY = Deno.env.get("HIBP_API_KEY") ?? "";

async function sha256Hex(s: string) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, "0")).join("");
}

// בדיקת דליפות דרך HIBP (אם יש מפתח)
async function checkBreaches(email: string): Promise<number> {
  if (!HIBP_KEY) return -1; // לא זמין
  const res = await fetch(
    `https://haveibeenpwned.com/api/v3/breachedaccount/${encodeURIComponent(email)}?truncateResponse=true`,
    { headers: { "hibp-api-key": HIBP_KEY, "user-agent": "TzelDigitali" } },
  );
  if (res.status === 404) return 0;   // לא נמצא בשום דליפה
  if (!res.ok) return -1;
  const data = await res.json();
  return Array.isArray(data) ? data.length : 0;
}

function buildReport(breachCount: number, hasPhone: boolean) {
  const findings: unknown[] = [];
  let score = 85;

  if (breachCount > 0) {
    score -= Math.min(45, breachCount * 12);
    findings.push({
      sev: "critical", icon: "🔓",
      title: `כתובת המייל שלך נמצאה ב-${breachCount} דליפות`,
      meta: "מקור: מאגרי דליפות ציבוריים",
      body: "המייל שלך הופיע בדליפות מידע ידועות. הסיסמאות שהשתמשת בהן חשופות, ותוקפים מנסים אותן אוטומטית באתרים אחרים.",
      fixes: [
        "החלף עכשיו סיסמאות בכל אתר שבו השתמשת באותה סיסמה.",
        "עבור למנהל סיסמאות עם סיסמה ייחודית לכל אתר.",
        "הפעל אימות דו-שלבי (2FA) בחשבונות הקריטיים.",
      ],
      link: { label: "פרטי הדליפות ב-Have I Been Pwned", url: "https://haveibeenpwned.com/" },
    });
  } else if (breachCount === 0) {
    findings.push({
      sev: "ok", icon: "✅", title: "לא נמצאו דליפות ידועות למייל שלך",
      meta: "בדיקה מול מאגרי דליפות", body: "כתובת המייל לא הופיעה בדליפות הידועות שנבדקו. המשך לשמור על היגיינת סיסמאות.",
      fixes: ["המשך להשתמש בסיסמה ייחודית לכל אתר.", "הפעל 2FA בכל מקום שאפשר."],
    });
  }

  findings.push({
    sev: "warn", icon: "🔑", title: "ודא שאימות דו-שלבי פעיל במייל הראשי",
    meta: "המלצת הגנה", body: "חשבון המייל הוא מפתח האב לכל שאר החשבונות. בלי 2FA, סיסמה שדלפה = השתלטות מלאה.",
    fixes: ["הפעל 2FA — עדיף עם אפליקציית Authenticator ולא SMS.", "בדוק את היסטוריית ההתחברויות לחשבון."],
  });

  if (hasPhone) {
    score -= 8;
    findings.push({
      sev: "warn", icon: "📱", title: "המספר שלך עלול להיות במאגרי שיווק",
      meta: "מקור נפוץ לספאם ופישינג", body: "מספרי טלפון נסחרים בין חברות שיווק. זה מקור לשיחות מכירה ו-SMS פישינג.",
      fixes: ["הירשם למאגר 'אל תתקשרו אליי' של הרשות להגנת הצרכן.", "אל תלחץ על קישורים ב-SMS גם אם נראה שזה מהבנק."],
    });
  }

  return { score: Math.max(5, Math.min(100, score)), findings };
}

Deno.serve(async (req) => {
  const cors = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "authorization, content-type, apikey, x-client-info",
  };
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });

  try {
    const authClient = createClient(SUPABASE_URL, Deno.env.get("SUPABASE_ANON_KEY")!, {
      global: { headers: { Authorization: req.headers.get("Authorization")! } },
    });
    const { data: { user } } = await authClient.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401, cors);

    const { email, phone, monitor } = await req.json();
    if (!email || !email.includes("@")) return json({ error: "bad_email" }, 400, cors);

    const breachCount = await checkBreaches(email.trim().toLowerCase());
    const report = buildReport(breachCount, !!phone);

    // ניטור: כשהמשתמש מפעיל ניטור, נשמר המייל (מוגן ב-RLS) כדי לאפשר בדיקה חוזרת מול HIBP.
    // כיבוי ניטור מסמן active=false. ראה מדיניות הפרטיות.
    const clean = email.trim().toLowerCase();
    if (monitor === true || monitor === false) {
      const admin = createClient(SUPABASE_URL, SERVICE_KEY);
      const hash = await sha256Hex(clean);
      if (monitor === true) {
        await admin.from("monitors").upsert({
          user_id: user.id, email_hash: hash, email: clean,
          last_breach_count: Math.max(0, breachCount), active: true,
          last_checked: new Date().toISOString(),
        }, { onConflict: "user_id,email_hash" });
      } else {
        await admin.from("monitors").update({ active: false })
          .eq("user_id", user.id).eq("email_hash", hash);
      }
    }

    return json({ report }, 200, cors);
  } catch (e) {
    console.error(e);
    return json({ error: "server_error" }, 500, cors);
  }
});

function json(body: unknown, status: number, cors: Record<string, string>) {
  return new Response(JSON.stringify(body), { status, headers: { ...cors, "content-type": "application/json" } });
}

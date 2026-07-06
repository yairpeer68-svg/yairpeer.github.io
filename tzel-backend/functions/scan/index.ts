// צל דיגיטלי — פונקציית סריקה (Supabase Edge Function, Deno)
//
// דוח חשיפה דיגיטלי מעמיק על המשתמש עצמו:
//  • כל דליפה בשמה, בתאריך, ובאילו פרטים נחשפו (HIBP, מלא)
//  • ריכוז "מה ידוע עליך" — כל סוגי המידע שדלפו
//  • הופעות ב-pastes (מזבלות מידע)
//  • פרופיל Gravatar ציבורי המקושר למייל
//
// עקרונות: self-check בלבד; מפתח HIBP חי רק בשרת.
// סוד נדרש: HIBP_API_KEY (מ-haveibeenpwned.com/API/Key).

import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const HIBP_KEY = Deno.env.get("HIBP_API_KEY") ?? "";
const HIBP_HEADERS = { "hibp-api-key": HIBP_KEY, "user-agent": "TzelDigitali" };

async function sha256Hex(s: string) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, "0")).join("");
}

interface Breach {
  Name: string; Title: string; Domain: string; BreachDate: string;
  PwnCount: number; DataClasses: string[]; IsSensitive: boolean; IsVerified: boolean;
}

// כל הדליפות המלאות (לא מקוצר)
async function fetchBreaches(email: string): Promise<Breach[] | null> {
  if (!HIBP_KEY) return null;
  const res = await fetch(
    `https://haveibeenpwned.com/api/v3/breachedaccount/${encodeURIComponent(email)}?truncateResponse=false`,
    { headers: HIBP_HEADERS },
  );
  if (res.status === 404) return [];
  if (!res.ok) return null;
  return await res.json();
}

// הופעות ב-pastes
async function fetchPastes(email: string): Promise<number> {
  if (!HIBP_KEY) return -1;
  const res = await fetch(
    `https://haveibeenpwned.com/api/v3/pasteaccount/${encodeURIComponent(email)}`,
    { headers: HIBP_HEADERS },
  );
  if (res.status === 404) return 0;
  if (!res.ok) return -1;
  const d = await res.json();
  return Array.isArray(d) ? d.length : 0;
}

// פרופיל Gravatar ציבורי המקושר למייל (חשיפת שם/תמונה)
async function hasGravatar(email: string): Promise<boolean> {
  try {
    const hash = await sha256Hex(email);
    const res = await fetch(`https://www.gravatar.com/avatar/${hash}?d=404&s=1`);
    return res.status === 200;
  } catch { return false; }
}

const DC: Record<string, string> = {
  "Email addresses": "כתובות מייל", "Passwords": "סיסמאות", "Usernames": "שמות משתמש",
  "Phone numbers": "מספרי טלפון", "Physical addresses": "כתובות מגורים", "Names": "שמות מלאים",
  "Dates of birth": "תאריכי לידה", "Genders": "מגדר", "IP addresses": "כתובות IP",
  "Geographic locations": "מיקום גאוגרפי", "Job titles": "תפקידים",
  "Social media profiles": "פרופילים ברשתות", "Website activity": "פעילות באתרים",
  "Password hints": "רמזים לסיסמה", "Security questions and answers": "שאלות אבטחה",
  "Partial credit card data": "פרטי אשראי חלקיים", "Credit cards": "כרטיסי אשראי",
  "Purchases": "רכישות", "Devices": "מכשירים", "Spoken languages": "שפות",
};
const he = (c: string) => DC[c] || c;
const yr = (d: string) => (d || "").slice(0, 4);

function buildReport(breaches: Breach[] | null, pastes: number, gravatar: boolean, hasPhone: boolean) {
  const findings: unknown[] = [];
  let score = 100;

  // HIBP לא זמין — דוח היוריסטי בסיסי
  if (breaches === null) {
    findings.push({
      sev: "warn", icon: "🔑", title: "ודא שאימות דו-שלבי פעיל במייל הראשי", meta: "המלצת הגנה",
      body: "בדיקת הדליפות אינה זמינה כרגע, אך זו ההגנה החשובה ביותר: חשבון המייל הוא מפתח האב לכל שאר החשבונות.",
      fixes: ["הפעל 2FA — עדיף אפליקציית Authenticator ולא SMS.", "בדוק היסטוריית התחברויות."],
    });
    return { score: 70, findings };
  }

  const sorted = [...breaches].sort((a, b) => (b.PwnCount || 0) - (a.PwnCount || 0));
  const allClasses = new Set<string>();
  let leakedPasswords = false;
  const pwBreaches: string[] = [];
  const sensitive: Breach[] = [];
  for (const b of breaches) {
    (b.DataClasses || []).forEach(c => allClasses.add(c));
    if ((b.DataClasses || []).includes("Passwords")) { leakedPasswords = true; pwBreaches.push(b.Title); }
    if (b.IsSensitive) sensitive.push(b);
  }

  if (breaches.length === 0) {
    findings.push({
      sev: "ok", icon: "✅", title: "לא נמצאת באף דליפה ידועה", meta: "בדיקה מול HIBP",
      body: "כתובת המייל שלך לא הופיעה בדליפות הידועות. זה מצוין — המשך לשמור על היגיינת סיסמאות ו-2FA.",
      fixes: ["המשך סיסמה ייחודית לכל אתר.", "הפעל 2FA בכל מקום."],
    });
  } else {
    // כותרת-על מפחידה
    score -= Math.min(40, breaches.length * 7);
    findings.push({
      sev: "critical", icon: "🔓",
      title: `נמצאת ב-${breaches.length} דליפות מידע`,
      meta: `${(allClasses.size)} סוגי מידע שלך נחשפו`,
      body: `כתובת המייל שלך הופיעה ב-${breaches.length} דליפות ידועות${leakedPasswords ? ", כולל דליפות שחשפו את הסיסמאות שלך" : ""}. ככל שהמידע שלך מפוזר ביותר מקומות, כך קל יותר לתוקף להתחזות אליך, לנחש סיסמאות ולהשתלט על חשבונות.`,
      fixes: [
        "החלף סיסמאות בכל אתר שבו השתמשת באותה סיסמה — עכשיו.",
        "עבור למנהל סיסמאות (Bitwarden חינמי) עם סיסמה ייחודית לכל אתר.",
        "הפעל אימות דו-שלבי בכל החשבונות הקריטיים.",
      ],
      link: { label: "צפה בכל הדליפות ב-Have I Been Pwned", url: "https://haveibeenpwned.com/" },
    });

    // סיסמאות שדלפו — הכי קריטי
    if (leakedPasswords) {
      score -= 20;
      findings.push({
        sev: "critical", icon: "🔑",
        title: "הסיסמאות שלך דלפו החוצה",
        meta: `ב-${pwBreaches.length} דליפות`,
        body: `בדליפות הבאות נחשפו סיסמאות שלך: ${pwBreaches.slice(0, 5).join(", ")}${pwBreaches.length > 5 ? " ועוד" : ""}. תוקפים לוקחים את הסיסמאות האלה ומנסים אותן אוטומטית בבנק, במייל וברשתות (Credential Stuffing).`,
        fixes: [
          "אם אתה עדיין משתמש באחת מהסיסמאות האלה — החלף אותה מיד.",
          "לעולם אל תשתמש באותה סיסמה בשני אתרים.",
          "הפעל 2FA כדי שגם סיסמה שדלפה לא תספיק לתוקף.",
        ],
      });
    }

    // כל דליפה גדולה בנפרד (עד 5)
    for (const b of sorted.slice(0, 5)) {
      const classes = (b.DataClasses || []).slice(0, 5).map(he).join(", ");
      const pw = (b.DataClasses || []).includes("Passwords");
      findings.push({
        sev: pw ? "critical" : "warn",
        icon: "🏢",
        title: `${b.Title}${b.BreachDate ? " · " + yr(b.BreachDate) : ""}`,
        meta: b.PwnCount ? `${(b.PwnCount / 1e6).toFixed(1)} מיליון חשבונות נפגעו` : "דליפה מאומתת",
        body: `בדליפה מ-${b.Title} נחשפו הפרטים הבאים שלך: ${classes || "פרטי חשבון"}.` + (b.IsSensitive ? " ⚠️ זו דליפה רגישה — עצם ההופעה בה עלולה להיות חושפנית." : ""),
        fixes: [
          `אם יש לך עדיין חשבון ב-${b.Title} — החלף שם סיסמה והפעל 2FA.`,
          pw ? "הסיסמה מהאתר הזה חשופה — אל תשתמש בה בשום מקום אחר." : "בדוק שלא השתמשת באותם פרטים באתרים נוספים.",
        ],
      });
    }

    // ריכוז החשיפה
    if (allClasses.size > 1) {
      findings.push({
        sev: "warn", icon: "📇",
        title: "מה ידוע עליך מהדליפות",
        meta: `${allClasses.size} סוגי מידע`,
        body: "סך כל הפרטים שלך שמסתובבים ברשת מהדליפות השונות: " + [...allClasses].map(he).join(" · ") + ". זה בדיוק החומר שמשמש לפישינג ממוקד והתחזות.",
        fixes: ["הנח שכל פרט כאן כבר פומבי — היזהר מהודעות שמשתמשות בו כדי לזכות באמונך.", "שקול כתובת מייל נפרדת להרשמות לא חשובות."],
      });
    }
  }

  // pastes
  if (pastes > 0) {
    score -= 8;
    findings.push({
      sev: "warn", icon: "📋",
      title: `המייל שלך הופיע ב-${pastes} מזבלות מידע (Pastes)`,
      meta: "אתרי הדבקה ציבוריים", body: "כתובתך פורסמה באתרי paste ציבוריים — לרוב תוצאה של פריצה או מכירת מאגרים. זה מגביר את הסיכון לספאם ולניסיונות פישינג.",
      fixes: ["הגבר ערנות להודעות פישינג לכתובת הזו.", "אם זו כתובת ראשית — שקול להעביר שירותים חשובים לכתובת נקייה."],
    });
  }

  // gravatar
  if (gravatar) {
    score -= 4;
    findings.push({
      sev: "info", icon: "🖼️",
      title: "יש לך פרופיל Gravatar ציבורי", meta: "מקושר למייל שלך",
      body: "המייל שלך מקושר לפרופיל Gravatar ציבורי — כל אתר שאתה מגיב בו יכול להציג את התמונה שלך, ולעיתים גם שם וקישורים לרשתות. כל מי שיודע את המייל שלך יכול לראות את זה.",
      fixes: ["היכנס ל-gravatar.com ובדוק אילו פרטים חשופים.", "הסר תמונה/שם אם אינך רוצה שיהיו מקושרים למייל הזה."],
    });
  }

  // 2FA כללי
  findings.push({
    sev: "warn", icon: "🛡️", title: "ודא אימות דו-שלבי במייל הראשי", meta: "ההגנה הקריטית ביותר",
    body: "חשבון המייל הוא מפתח האב — מי שנכנס אליו מאפס סיסמאות בכל שאר האתרים. בלי 2FA, סיסמה שדלפה = השתלטות מלאה.",
    fixes: ["הפעל 2FA עם אפליקציית Authenticator (לא SMS).", "עדכן כתובת/טלפון שחזור.", "בדוק 'פעילות אחרונה' לחשבון."],
  });

  // טלפון
  if (hasPhone) {
    score -= 6;
    findings.push({
      sev: "warn", icon: "📱", title: "המספר שלך עלול להיות במאגרי שיווק", meta: "מקור לספאם ופישינג",
      body: "מספרי טלפון נסחרים בין חברות שיווק וספאם — מקור לשיחות מכירה, SMS פישינג והודעות וואטסאפ מזויפות.",
      fixes: ["הירשם למאגר 'אל תתקשרו אליי' של הרשות להגנת הצרכן.", "אל תלחץ על קישורים ב-SMS — גם אם נראה שזה מהבנק."],
    });
  }

  return { score: Math.max(5, Math.min(100, Math.round(score))), findings, breachCount: breaches.length };
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
    const clean = email.trim().toLowerCase();

    const [breaches, pastes, gravatar] = await Promise.all([
      fetchBreaches(clean),
      fetchPastes(clean),
      hasGravatar(clean),
    ]);
    const report = buildReport(breaches, pastes, gravatar, !!phone);
    const breachCount = Array.isArray(breaches) ? breaches.length : 0;

    // ניטור: המייל נשמר (מוגן ב-RLS) רק כשהמשתמש מפעיל ניטור. ראה מדיניות פרטיות.
    if (monitor === true || monitor === false) {
      const admin = createClient(SUPABASE_URL, SERVICE_KEY);
      const hash = await sha256Hex(clean);
      if (monitor === true) {
        await admin.from("monitors").upsert({
          user_id: user.id, email_hash: hash, email: clean,
          last_breach_count: breachCount, active: true, last_checked: new Date().toISOString(),
        }, { onConflict: "user_id,email_hash" });
      } else {
        await admin.from("monitors").update({ active: false }).eq("user_id", user.id).eq("email_hash", hash);
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

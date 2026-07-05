// פקיד — פונקציית השרת שמנתחת מכתבים (Supabase Edge Function, Deno)
//
// מקבלת צילום מכתב, שולחת ל-Claude לניתוח, אוכפת מכסת שימוש,
// שומרת את התוצאה במסד הנתונים ומחזירה ניתוח מובנה בעברית.
//
// סודות נדרשים (Settings → Edge Functions → Secrets):
//   ANTHROPIC_API_KEY — מפתח API מ-console.anthropic.com

import { createClient } from "npm:@supabase/supabase-js@2";

const ANTHROPIC_KEY = Deno.env.get("ANTHROPIC_API_KEY")!;
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const SYSTEM_PROMPT = `אתה "פקיד" — עוזר ישראלי שמתרגם מכתבים רשמיים (ביטוח לאומי, מס הכנסה, עיריות, בנקים, הוצאה לפועל) לעברית פשוטה של בני אדם.

נתח את המכתב שבתמונה והחזר JSON בלבד (בלי טקסט נוסף) במבנה הבא:
{
  "sender": "מי שלח את המכתב",
  "title": "כותרת של משפט אחד שמסבירה מה המכתב באמת אומר",
  "urgency": "high | med | low",
  "urgencyText": "תיאור קצר של רמת הדחיפות",
  "summary": "הסבר של 2-4 משפטים בעברית פשוטה ומרגיעה: מה קרה ולמה קיבלת את זה",
  "demand": "מה בדיוק רוצים ממך",
  "deadline": "עד מתי צריך להגיב, לפי מה שכתוב במכתב",
  "steps": ["צעד 1", "צעד 2", "..."] (3-6 צעדים מעשיים וקונקרטיים),
  "draft": "טיוטת מכתב תשובה/השגה מוכנה לשליחה, עם [סוגריים] במקומות שצריך להשלים",
  "calm": "משפט מרגיע ואמפתי שמתחיל ב-😌 ושם את המכתב בפרופורציה"
}

כללים:
- עברית פשוטה, בלי ז'רגון משפטי. גובה עיניים, בן אדם שעוזר לחבר.
- אם יש תאריך/סכום במכתב — צטט אותו במדויק.
- אם המכתב לא ברור/חתוך — כתוב זאת ב-summary ובקש לצלם שוב.
- לעולם אל תמציא עובדות שלא מופיעות במכתב.
- אתה לא עורך דין: בצעדים למקרים כבדים (הוצאה לפועל, תביעות) כלול המלצה לפנות לסיוע משפטי (כולל הסיוע המשפטי החינמי של משרד המשפטים).`;

Deno.serve(async (req) => {
  const cors = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "authorization, content-type, apikey, x-client-info",
  };
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });

  try {
    // זיהוי המשתמש מהטוקן
    const authClient = createClient(SUPABASE_URL, Deno.env.get("SUPABASE_ANON_KEY")!, {
      global: { headers: { Authorization: req.headers.get("Authorization")! } },
    });
    const { data: { user } } = await authClient.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401, cors);

    const admin = createClient(SUPABASE_URL, SERVICE_KEY);

    // אכיפת מכסה: 3 ניתוחים בחודש בחינם, ללא הגבלה בפרימיום
    let { data: profile } = await admin.from("profiles").select("*").eq("id", user.id).single();
    if (!profile) {
      await admin.from("profiles").insert({ id: user.id });
      profile = { scans_used: 0, scans_limit: 3, is_premium: false, period_start: null };
    }
    const monthStart = new Date().toISOString().slice(0, 7) + "-01";
    if (profile.period_start !== monthStart) {
      await admin.from("profiles").update({ scans_used: 0, period_start: monthStart }).eq("id", user.id);
      profile.scans_used = 0;
    }
    if (!profile.is_premium && profile.scans_used >= profile.scans_limit) {
      return json({ error: "quota", message: "נגמרה המכסה החודשית החינמית" }, 402, cors);
    }

    const { image, mime } = await req.json();
    if (!image) return json({ error: "missing image" }, 400, cors);

    // ניתוח עם Claude
    const aiRes = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "x-api-key": ANTHROPIC_KEY,
        "anthropic-version": "2023-06-01",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        model: "claude-sonnet-5",
        max_tokens: 2000,
        system: SYSTEM_PROMPT,
        messages: [{
          role: "user",
          content: [
            { type: "image", source: { type: "base64", media_type: mime || "image/jpeg", data: image } },
            { type: "text", text: "נתח את המכתב הזה והחזר JSON בלבד." },
          ],
        }],
      }),
    });
    if (!aiRes.ok) {
      console.error("anthropic error", aiRes.status, await aiRes.text());
      return json({ error: "ai_failed" }, 502, cors);
    }
    const aiData = await aiRes.json();
    const text = aiData.content?.[0]?.text ?? "";
    const analysis = JSON.parse(text.slice(text.indexOf("{"), text.lastIndexOf("}") + 1));

    // שמירה ועדכון מכסה
    await admin.from("scans").insert({ user_id: user.id, analysis });
    await admin.from("profiles").update({ scans_used: profile.scans_used + 1 }).eq("id", user.id);

    return json({ analysis }, 200, cors);
  } catch (e) {
    console.error(e);
    return json({ error: "server_error" }, 500, cors);
  }
});

function json(body: unknown, status: number, cors: Record<string, string>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "content-type": "application/json" },
  });
}

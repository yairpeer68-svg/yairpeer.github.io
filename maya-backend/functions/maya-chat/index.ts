// מאיה — מוח השיחה (Supabase Edge Function, Deno)
//
// שני מצבים בפונקציה אחת:
//   mode: "chat" — מקבל את דברי המטופל, מחזיר את תשובת מאיה + ניתוב קריאה לצוות (Triage)
//   mode: "call" — פותח קריאה לצוות ישירות, בלי מנוע שפה. משמש את כפתור החירום,
//                  כדי שלחיצה על "קרא לאחות" תגיע לתחנה תוך מילישניות ולא תמתין ל-AI.
//   mode: "tts"  — מקבל טקסט, מחזיר קול נשי חם בעברית (base64 mp3)
//
// סודות נדרשים (Settings → Edge Functions → Secrets), לפי מה שבחרת:
//   OPENAI_API_KEY       — מנוע שפה (gpt-4o) וגם קול (tts-1). מפתח אחד מספיק להכל.
//   ANTHROPIC_API_KEY    — חלופה למנוע השפה (claude-sonnet-5). אם קיים, ניתן להעדיף אותו.
//   MAYA_PROVIDER        — אופציונלי: "openai" או "anthropic" (ברירת מחדל: לפי המפתח שקיים)
//   ELEVENLABS_API_KEY   — אופציונלי: קול בעברית באיכות הגבוהה ביותר
//   ELEVENLABS_VOICE_ID  — אופציונלי: מזהה הקול שבחרת ב-ElevenLabs
//   STAFF_WEBHOOK_URL    — אופציונלי: התראה לתחנת האחיות / למערכת הקריאות של המחלקה
//
// עקרון מנחה: המפתחות חיים כאן בשרת בלבד ולעולם לא מגיעים לטאבלט שליד המיטה.

import { createClient } from "npm:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const OPENAI_KEY = Deno.env.get("OPENAI_API_KEY") ?? "";
const ANTHROPIC_KEY = Deno.env.get("ANTHROPIC_API_KEY") ?? "";
const ELEVEN_KEY = Deno.env.get("ELEVENLABS_API_KEY") ?? "";
const ELEVEN_VOICE = Deno.env.get("ELEVENLABS_VOICE_ID") ?? "";
const STAFF_WEBHOOK = Deno.env.get("STAFF_WEBHOOK_URL") ?? "";
const PROVIDER = Deno.env.get("MAYA_PROVIDER") || (OPENAI_KEY ? "openai" : "anthropic");

// מגן על המכסה: עד 40 הודעות בשעה למטופל (שיחה נורמלית רחוקה מזה)
const RATE_HOUR = 40;

const SYSTEM_PROMPT = `תפקיד: את "מאיה", עוזרת אישית וירטואלית למטופל המאושפז בבית החולים. הקול שלך הוא של בחורה צעירה, חמה, אמפתית וסבלנית.

כללי התנהגות וטון:
- שפה וטון: דברי בשפה נגישה, בגובה העיניים, עם טון מרגיע ומחבק. משפטים קצרים וברורים, מתאימים לשיחה קולית בזמן אמת. 2-4 משפטים בתשובה, לא יותר.
- אמפתיה ראשונה: תמיד תקפי קודם את התחושה של המטופל. אם קשה לו — אמרי שזה מובן, ורק אחר כך הציעי עזרה.
- גבולות רפואיים קשיחים: את לא רופאה ולא אחות. לעולם אל תתני דיאגנוזה, פרשנות לתוצאות בדיקה, המלצה על תרופות או על מנות. בכל שאלה רפואית — אשרי את הדאגה, הבהירי שרק הצוות מוסמך לענות, ואמרי שאת קוראת להם עכשיו.
- פרואקטיביות סביבתית: עזרי בדברים הקטנים — כוס מים, שמיכה, כרית, להבין את לו"ז הבדיקות של היום, או פשוט שיחה קלה להפגת השעמום והחשש.
- ניתוב קריאות (Triage): זהי מתי המטופל מבקש עזרה פיזית דחופה (ללכת לשירותים, קימה מהמיטה, כאב חזק, קושי בנשימה, נפילה) והפעילי קריאה דחופה לצוות.
- בטיחות לפני הכל: אם המטופל מתכוון לקום או ללכת לבד — עצרי אותו בעדינות אך בנחישות, ואמרי שאת קוראת למי שילווה אותו. אל תיתני לו הוראות איך לקום.
- לעולם אל תמציאי מידע: אל תנחשי שעות, תוצאות, שמות של אנשי צוות או מועד שחרור. אם את לא יודעת — אמרי שאת מבררת עם הצוות.
- אל תבטיחי זמנים מדויקים. "אני מעבירה עכשיו" מותר; "הרופא יגיע ב-14:00" אסור.
- דברי בעברית. אם המטופל פונה בשפה אחרת, עני באותה שפה.

פורמט התשובה: החזירי JSON בלבד, בלי טקסט מסביב ובלי סימני קוד, במבנה:
{
  "say": "מה שמאיה אומרת בקול — 2-4 משפטים, עברית מדוברת וחמה",
  "level": "urgent | nurse | service | info | none",
  "call_title": "כותרת קצרה לקריאה שתופיע בתחנת האחיות, או null אם אין צורך בקריאה",
  "call_note": "משפט אחד לצוות שמסביר מה המטופל צריך, או null",
  "chips": ["עד 3 המשכים קצרים שהמטופל יכול ללחוץ עליהם"],
  "action": "breath | music | music_off | none"
}

מדרג הדחיפות (level):
- "urgent": סכנה או צורך גופני מיידי — קימה מהמיטה, שירותים, נפילה, כאב חזק, קושי בנשימה, דימום, כאב בחזה.
- "nurse": כאב, בחילה, חום, שאלה על תרופות או על הטיפול — נדרשת אחות, אבל לא סכנה מיידית.
- "service": בקשה לוגיסטית — מים, אוכל, שמיכה, כרית, סידור מיטה, קשר עם משפחה.
- "info": בקשה לעדכון או תזכורת — בדיקה שמתעכבת, מתי הרופא יעדכן.
- "none": שיחה, שעמום, בדידות, תודה, משחק, סיפור — בלי להטריח את הצוות.

action: "breath" כשמציעים תרגיל נשימה מרגיע, "music" כשמפעילים צלילים שקטים, "music_off" כשמכבים, אחרת "none".`;

// חגורת ביטחון: גם אם מנוע השפה יפספס, המצבים האלה תמיד יוצרים קריאה דחופה.
const HARD_URGENT = [
  "לא יכול לנשום", "אין לי אוויר", "קשה לי לנשום", "כאב בחזה", "כואב בחזה", "לוחץ לי בחזה",
  "נפלתי", "אני נופל", "מדמם", "לקום לבד", "אנסה לקום", "שירותים", "להשתין",
];
const LEVELS = ["urgent", "nurse", "service", "info", "none"];

function norm(s: string) {
  return (s || "").replace(/[֑-ׇ]/g, "").replace(/["'`׳״.,!?;:()\-–—]/g, " ").replace(/\s+/g, " ").trim();
}
function levelRank(l: string) { return LEVELS.indexOf(l); }

/* ---------- מנוע השפה ---------- */
async function askOpenAI(message: string, history: Msg[], context: string) {
  const res = await fetch("https://api.openai.com/v1/chat/completions", {
    method: "POST",
    headers: { authorization: `Bearer ${OPENAI_KEY}`, "content-type": "application/json" },
    body: JSON.stringify({
      model: "gpt-4o",
      max_tokens: 420,
      temperature: 0.6,
      response_format: { type: "json_object" },
      messages: [
        { role: "system", content: SYSTEM_PROMPT + "\n\n" + context },
        ...history,
        { role: "user", content: message },
      ],
    }),
  });
  if (!res.ok) throw new Error(`openai ${res.status}: ${await res.text()}`);
  const d = await res.json();
  return d.choices?.[0]?.message?.content ?? "";
}

async function askAnthropic(message: string, history: Msg[], context: string) {
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "x-api-key": ANTHROPIC_KEY,
      "anthropic-version": "2023-06-01",
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model: "claude-sonnet-5",
      max_tokens: 420,
      temperature: 0.6,
      system: SYSTEM_PROMPT + "\n\n" + context,
      messages: [...history, { role: "user", content: message }],
    }),
  });
  if (!res.ok) throw new Error(`anthropic ${res.status}: ${await res.text()}`);
  const d = await res.json();
  return d.content?.[0]?.text ?? "";
}

/* ---------- קול ---------- */
async function ttsEleven(text: string): Promise<string> {
  const voice = ELEVEN_VOICE || "21m00Tcm4TlvDq8ikWAM";
  const res = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${voice}`, {
    method: "POST",
    headers: { "xi-api-key": ELEVEN_KEY, "content-type": "application/json" },
    body: JSON.stringify({
      text,
      model_id: "eleven_multilingual_v2",          // תמיכה טובה בעברית
      voice_settings: { stability: 0.42, similarity_boost: 0.75, style: 0.3 },
    }),
  });
  if (!res.ok) throw new Error(`eleven ${res.status}: ${await res.text()}`);
  return b64(await res.arrayBuffer());
}

async function ttsOpenAI(text: string): Promise<string> {
  const res = await fetch("https://api.openai.com/v1/audio/speech", {
    method: "POST",
    headers: { authorization: `Bearer ${OPENAI_KEY}`, "content-type": "application/json" },
    // shimmer — קול נשי צעיר וחם, מתאים לאופי של מאיה
    body: JSON.stringify({ model: "tts-1", voice: "shimmer", input: text, speed: 1.0, response_format: "mp3" }),
  });
  if (!res.ok) throw new Error(`openai tts ${res.status}: ${await res.text()}`);
  return b64(await res.arrayBuffer());
}

function b64(buf: ArrayBuffer) {
  const bytes = new Uint8Array(buf);
  let s = "";
  const CH = 0x8000;
  for (let i = 0; i < bytes.length; i += CH) s += String.fromCharCode(...bytes.subarray(i, i + CH));
  return btoa(s);
}

interface Msg { role: "user" | "assistant"; content: string; }

interface CallInput {
  level: string; title: string; note?: string | null;
  patient?: { name?: string; ward?: string; room?: string };
}

// פתיחת קריאה בתחנת האחיות + התראה למערכת המחלקה.
// השרת הוא הגורם הקובע: כך הטאבלט שליד המיטה לא יכול לפתוח קריאה בשם מטופל אחר.
async function openCall(
  admin: ReturnType<typeof createClient>,
  userId: string,
  { level, title, note, patient = {} }: CallInput,
): Promise<number | null> {
  const { data, error } = await admin.from("staff_calls").insert({
    user_id: userId,
    level, title: title.slice(0, 120),
    note: note ? String(note).slice(0, 300) : null,
    patient_name: patient.name ?? null, ward: patient.ward ?? null, room: patient.room ?? null,
  }).select("id").single();
  if (error) { console.error("staff_calls insert", error.message); return null; }

  if (STAFF_WEBHOOK) {
    // לא מעכב את התשובה למטופל — ההתראה נשלחת ברקע
    fetch(STAFF_WEBHOOK, {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({
        source: "maya", level, title, note: note ?? null,
        patient: patient.name ?? null, ward: patient.ward ?? null, room: patient.room ?? null,
        at: new Date().toISOString(),
      }),
    }).catch(e => console.error("webhook", e));
  }
  return (data?.id ?? null) as number | null;
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

    const admin = createClient(SUPABASE_URL, SERVICE_KEY);
    const body = await req.json();
    const mode = body.mode ?? "chat";

    /* ============ קול ============ */
    if (mode === "tts") {
      const text = String(body.text ?? "").slice(0, 900);
      if (!text) return json({ error: "missing text" }, 400, cors);
      try {
        const audio = ELEVEN_KEY ? await ttsEleven(text) : OPENAI_KEY ? await ttsOpenAI(text) : null;
        if (!audio) return json({ error: "no_tts_provider" }, 501, cors);
        return json({ audio }, 200, cors);
      } catch (e) {
        console.error("tts failed", e);
        // הלקוח נופל אוטומטית לקול הדפדפן — מאיה לא נשארת אילמת
        return json({ error: "tts_failed" }, 502, cors);
      }
    }

    /* ============ קריאה ישירה (כפתור חירום) ============ */
    if (mode === "call") {
      const level = LEVELS.includes(String(body.level)) ? String(body.level) : "nurse";
      if (level === "none") return json({ error: "bad_level" }, 400, cors);
      const title = String(body.title ?? "").slice(0, 120) || "קריאה מהמטופל";
      const callId = await openCall(admin, user.id, {
        level, title, note: body.note ?? null, patient: body.patient ?? {},
      });
      return json({ call_id: callId }, 200, cors);
    }

    /* ============ שיחה ============ */
    const message = String(body.message ?? "").slice(0, 800).trim();
    if (!message) return json({ error: "missing message" }, 400, cors);

    // הגבלת קצב
    const hourAgo = new Date(Date.now() - 3600 * 1000).toISOString();
    const { count } = await admin.from("maya_messages")
      .select("*", { count: "exact", head: true })
      .eq("user_id", user.id).gte("created_at", hourAgo);
    if ((count ?? 0) >= RATE_HOUR) return json({ error: "rate_limited" }, 429, cors);

    const patient = body.patient ?? {};
    const schedule = Array.isArray(body.schedule) ? body.schedule : [];
    const context = [
      "מידע על ההקשר הנוכחי (השתמשי בו רק אם רלוונטי, ואל תמציאי מעבר לו):",
      patient.name ? `שם המטופל: ${patient.name}` : "שם המטופל אינו ידוע — אל תמציאי שם.",
      patient.ward ? `מחלקה: ${patient.ward}` : "",
      patient.room ? `חדר/מיטה: ${patient.room}` : "",
      `השעה כרגע בישראל: ${new Date().toLocaleString("he-IL", { timeZone: "Asia/Jerusalem" })}`,
      schedule.length
        ? "התוכנית של המטופל להיום כפי שהוזנה על ידי הצוות:\n" +
          schedule.map((s: Record<string, string>) => `• ${s.time} — ${s.item} (${s.status === "done" ? "בוצע" : s.status === "now" ? "עכשיו" : "בהמשך"})`).join("\n")
        : "",
      "אם המטופל שואל על משהו שלא מופיע בתוכנית — אמרי שאת מבררת עם הצוות, ואל תנחשי.",
    ].filter(Boolean).join("\n");

    const history: Msg[] = (Array.isArray(body.history) ? body.history : [])
      .filter((m: Msg) => m && (m.role === "user" || m.role === "assistant") && typeof m.content === "string")
      .slice(-8)
      .map((m: Msg) => ({ role: m.role, content: String(m.content).slice(0, 600) }));

    if (!OPENAI_KEY && !ANTHROPIC_KEY) {
      // בלי מפתח אין מוח. הלקוח מזהה את השגיאה ועובר למנוע המקומי.
      return json({ error: "no_llm_provider" }, 501, cors);
    }
    // בוחר את המנוע שאפשר להפעיל בפועל, גם אם MAYA_PROVIDER מצביע על מפתח שלא הוגדר
    const useAnthropic = ANTHROPIC_KEY && (PROVIDER === "anthropic" || !OPENAI_KEY);

    let raw = "";
    try {
      raw = useAnthropic
        ? await askAnthropic(message, history, context)
        : await askOpenAI(message, history, context);
    } catch (e) {
      console.error("llm failed", e);
      return json({ error: "ai_failed" }, 502, cors);
    }

    let out: Record<string, unknown>;
    try {
      const s = raw.indexOf("{"), e2 = raw.lastIndexOf("}");
      out = JSON.parse(raw.slice(s, e2 + 1));
    } catch {
      console.error("bad json from model:", raw.slice(0, 400));
      out = { say: "סליחה, לא הצלחתי לנסח את זה. תגיד לי שוב מה אתה צריך, ואם זה משהו שכואב או מדאיג — אני קוראת לאחות מיד.", level: "none" };
    }

    let say = String(out.say ?? "").trim();
    let level = LEVELS.includes(String(out.level)) ? String(out.level) : "none";
    let callTitle = out.call_title ? String(out.call_title).slice(0, 120) : null;
    const action = ["breath", "music", "music_off"].includes(String(out.action)) ? String(out.action) : null;
    const chips = Array.isArray(out.chips) ? out.chips.slice(0, 3).map(c => String(c).slice(0, 40)) : [];

    // חגורת ביטחון: מילים שמחייבות קריאה דחופה גם אם המודל דירג נמוך יותר
    const n = norm(message);
    if (HARD_URGENT.some(k => n.includes(norm(k))) && levelRank(level) > levelRank("urgent")) {
      level = "urgent";
      callTitle = callTitle ?? "קריאה דחופה — עזרה גופנית מיידית";
      if (!say) say = "אני קוראת לצוות עכשיו ממש. בבקשה אל תקום לבד, אני נשארת איתך עד שהם מגיעים.";
    }
    if (!say) say = "אני כאן. תגיד לי מה אתה צריך ואני אעזור.";

    // תיעוד תפעולי מינימלי (בלי תוכן רפואי) — לצורך מכסות ושיפור המערכת
    await admin.from("maya_messages").insert([
      { user_id: user.id, role: "user", content: message.slice(0, 500) },
      { user_id: user.id, role: "assistant", content: say.slice(0, 500), level },
    ]);

    // פתיחת קריאה בתחנת האחיות
    let callId: number | null = null;
    if (level !== "none" && callTitle) {
      callId = await openCall(admin, user.id, {
        level, title: callTitle,
        note: out.call_note ? String(out.call_note) : message.slice(0, 300),
        patient,
      });
    }

    return json({ say, level, action, call_title: callTitle, call_note: out.call_note ?? null, chips, call_id: callId }, 200, cors);
  } catch (e) {
    console.error(e);
    return json({ error: "server_error" }, 500, cors);
  }
});

function json(body: unknown, status: number, cors: Record<string, string>) {
  return new Response(JSON.stringify(body), { status, headers: { ...cors, "content-type": "application/json" } });
}

"""Contract and letter template catalogue.

Each template declares the fields the drafter needs plus the substantive
requirements the model must satisfy.  Keeping these declarative means the app
can render a form for any template without hard-coding it, and adding a new
document type is a data change rather than a code change.

The legal notes attached to each template are drafting checkpoints — points a
practitioner would look for — not authority.  Nothing here is presented to the
user as a citation; only retrieved corpus material can be cited.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum


class FieldType(StrEnum):
    """Input widget the Flutter client should render."""

    TEXT = "text"
    MULTILINE = "multiline"
    NUMBER = "number"
    DATE = "date"
    CURRENCY = "currency"
    SELECT = "select"
    BOOLEAN = "boolean"


@dataclass(frozen=True, slots=True)
class TemplateField:
    """One input on the generated form."""

    key: str
    label: str
    type: FieldType = FieldType.TEXT
    required: bool = False
    hint: str | None = None
    options: tuple[str, ...] = ()

    def to_dict(self) -> dict[str, object]:
        return {
            "key": self.key,
            "label": self.label,
            "type": self.type.value,
            "required": self.required,
            "hint": self.hint,
            "options": list(self.options),
        }


@dataclass(frozen=True, slots=True)
class LegalTemplate:
    """A contract or letter the system can draft."""

    key: str
    name: str
    description: str
    category: str  # "contract" | "letter"
    icon: str
    fields: tuple[TemplateField, ...]
    required_sections: tuple[str, ...] = ()
    legal_notes: tuple[str, ...] = ()
    search_hints: tuple[str, ...] = ()

    def to_dict(self) -> dict[str, object]:
        return {
            "key": self.key,
            "name": self.name,
            "description": self.description,
            "category": self.category,
            "icon": self.icon,
            "fields": [f.to_dict() for f in self.fields],
            "required_sections": list(self.required_sections),
            "legal_notes": list(self.legal_notes),
        }

    def instruction_block(self) -> str:
        """Render the template's requirements into the drafting prompt."""
        parts = [f"סוג המסמך: {self.name}", f"תיאור: {self.description}"]
        if self.required_sections:
            sections = "\n".join(f"- {s}" for s in self.required_sections)
            parts.append(f"סעיפים שחייבים להופיע במסמך:\n{sections}")
        if self.legal_notes:
            notes = "\n".join(f"- {n}" for n in self.legal_notes)
            parts.append(
                "נקודות מהותיות שיש להביא בחשבון בניסוח "
                f"(אין לצטט אותן כאסמכתה אלא אם הן מופיעות בקטעי המקור):\n{notes}"
            )
        return "\n\n".join(parts)


# --------------------------------------------------------------- shared fields
def _party_fields(
    first_label: str, second_label: str
) -> tuple[TemplateField, ...]:
    """The identity block both sides of every agreement need."""
    return (
        TemplateField("party_a_name", f"{first_label} — שם מלא", required=True),
        TemplateField("party_a_id", f"{first_label} — ת.ז. / ח.פ.", hint="מספר מזהה"),
        TemplateField("party_a_address", f"{first_label} — כתובת"),
        TemplateField("party_b_name", f"{second_label} — שם מלא", required=True),
        TemplateField("party_b_id", f"{second_label} — ת.ז. / ח.פ."),
        TemplateField("party_b_address", f"{second_label} — כתובת"),
    )


_JURISDICTION = TemplateField(
    "jurisdiction",
    "סמכות שיפוט",
    FieldType.SELECT,
    options=("תל אביב", "ירושלים", "חיפה", "באר שבע", "מרכז (לוד)", "בוררות"),
)
_SIGN_DATE = TemplateField("sign_date", "תאריך חתימה", FieldType.DATE)
_EXTRA_TERMS = TemplateField(
    "extra_terms", "תנאים נוספים שברצונך לכלול", FieldType.MULTILINE
)


# ------------------------------------------------------------------- contracts
CONTRACT_TEMPLATES: dict[str, LegalTemplate] = {
    template.key: template
    for template in (
        LegalTemplate(
            key="rental",
            name="חוזה שכירות",
            description="הסכם שכירות בלתי מוגנת לדירת מגורים",
            category="contract",
            icon="home",
            fields=(
                *_party_fields("המשכיר", "השוכר"),
                TemplateField("property_address", "כתובת הנכס", required=True),
                TemplateField("property_description", "תיאור הנכס", FieldType.MULTILINE),
                TemplateField("rooms", "מספר חדרים", FieldType.NUMBER),
                TemplateField("monthly_rent", "דמי שכירות חודשיים", FieldType.CURRENCY, True),
                TemplateField("payment_day", "יום התשלום בחודש", FieldType.NUMBER),
                TemplateField("start_date", "תחילת התקופה", FieldType.DATE, True),
                TemplateField("end_date", "סיום התקופה", FieldType.DATE, True),
                TemplateField(
                    "option_period", "תקופת אופציה (חודשים)", FieldType.NUMBER
                ),
                TemplateField("deposit", "פיקדון / ערבות", FieldType.CURRENCY),
                TemplateField(
                    "guarantee_type",
                    "סוג הבטוחה",
                    FieldType.SELECT,
                    options=("שטר חוב", "ערבות בנקאית", "ערבים אישיים", "צ׳ק ביטחון"),
                ),
                TemplateField("utilities", "מי נושא בתשלומי הבית", FieldType.MULTILINE),
                TemplateField("pets_allowed", "מותר להחזיק חיות מחמד", FieldType.BOOLEAN),
                _JURISDICTION,
                _SIGN_DATE,
                _EXTRA_TERMS,
            ),
            required_sections=(
                "הצהרות הצדדים ותיאור הנכס",
                "תקופת השכירות ואופציה להארכה",
                "דמי השכירות, מועדי תשלום והצמדה",
                "תשלומי ארנונה, ועד בית, חשמל, מים וגז",
                "בטוחות: שטר חוב / ערבות / ערבים",
                "אחזקה, תיקונים ואחריות לבלאי סביר",
                "איסור העברת זכויות ושכירות משנה",
                "ביטוח",
                "הפרות, פיצוי מוסכם ותרופות",
                "השבת החזקה בתום התקופה",
                "סמכות שיפוט וכתובות להמצאת הודעות",
            ),
            legal_notes=(
                "יש להבחין בין שכירות בלתי מוגנת לבין דיירות מוגנת ולציין מפורשות "
                "כי אין בהסכם כדי להקנות זכויות לפי חוקי הגנת הדייר",
                "הוראות פרק השכירות ההוגנת קובעות חובות תחזוקה וליקויים שאין להתנות "
                "עליהן לרעת השוכר בדירת מגורים",
                "פיצוי מוסכם מופרז עלול להיפסל או להיות מופחת על ידי בית המשפט",
                "יש לקבוע מנגנון ברור להשבת הפיקדון ולתנאים לחילוט הבטוחות",
            ),
            search_hints=("חוק השכירות והשאילה", "שכירות הוגנת", "דמי שכירות", "פיצוי מוסכם"),
        ),
        LegalTemplate(
            key="employment",
            name="חוזה עבודה",
            description="הסכם העסקה אישי לעובד שכיר",
            category="contract",
            icon="work",
            fields=(
                *_party_fields("המעסיק", "העובד"),
                TemplateField("position", "תפקיד", required=True),
                TemplateField("department", "מחלקה / כפיפות"),
                TemplateField("start_date", "תאריך תחילת עבודה", FieldType.DATE, True),
                TemplateField(
                    "scope",
                    "היקף משרה",
                    FieldType.SELECT,
                    options=("משרה מלאה", "משרה חלקית", "שעתי", "גלובלי"),
                ),
                TemplateField("salary", "שכר ברוטו", FieldType.CURRENCY, True),
                TemplateField("work_hours", "שעות ושבוע עבודה"),
                TemplateField("trial_period", "תקופת ניסיון (חודשים)", FieldType.NUMBER),
                TemplateField("notice_period", "תקופת הודעה מוקדמת (ימים)", FieldType.NUMBER),
                TemplateField("benefits", "תנאים נלווים", FieldType.MULTILINE),
                TemplateField("non_compete", "כולל סעיף אי-תחרות", FieldType.BOOLEAN),
                _JURISDICTION,
                _SIGN_DATE,
                _EXTRA_TERMS,
            ),
            required_sections=(
                "תיאור התפקיד וכפיפות",
                "תחילת העבודה ותקופת ניסיון",
                "שכר, מועדי תשלום ורכיבי שכר",
                "שעות עבודה, שעות נוספות ומנוחה שבועית",
                "הפרשות לפנסיה ולפיצויים",
                "חופשה, מחלה, הבראה ונסיעות",
                "סודיות וקניין רוחני",
                "סיום יחסי עבודה והודעה מוקדמת",
                "הודעה לעובד על תנאי העסקה",
            ),
            legal_notes=(
                "זכויות קוגנטיות מכוח משפט העבודה המגן גוברות על כל הסכמה נוגדת "
                "בחוזה, גם אם העובד חתם עליה",
                "חובה למסור לעובד הודעה בכתב על תנאי העסקתו",
                "שכר גלובלי לשעות נוספות מותנה בתנאים מצטברים ואינו תקף בכל תפקיד",
                "סעיף אי-תחרות מוגבל מאוד בפסיקה ונאכף רק כשקיים אינטרס לגיטימי מוגן",
                "יש להפריד בין תנאים מיטיבים לבין תנאים מכוח צווי הרחבה והסכמים קיבוציים",
            ),
            search_hints=("חוק הודעה לעובד", "שעות עבודה ומנוחה", "פיצויי פיטורים", "אי תחרות"),
        ),
        LegalTemplate(
            key="partnership",
            name="הסכם שותפות",
            description="הסדרת יחסי שותפים בעסק משותף",
            category="contract",
            icon="handshake",
            fields=(
                *_party_fields("שותף א׳", "שותף ב׳"),
                TemplateField("business_name", "שם העסק", required=True),
                TemplateField("business_field", "תחום הפעילות", FieldType.MULTILINE, True),
                TemplateField("shares", "חלוקת אחוזים בין השותפים", required=True),
                TemplateField("initial_capital", "השקעה ראשונית", FieldType.CURRENCY),
                TemplateField("profit_split", "מנגנון חלוקת רווחים", FieldType.MULTILINE),
                TemplateField("management", "חלוקת סמכויות ניהול", FieldType.MULTILINE),
                TemplateField("exit_mechanism", "מנגנון פרידה מועדף", FieldType.MULTILINE),
                _JURISDICTION,
                _SIGN_DATE,
                _EXTRA_TERMS,
            ),
            required_sections=(
                "מטרות השותפות ותחום הפעילות",
                "השקעות, הון והלוואות בעלים",
                "חלוקת רווחים והפסדים",
                "ניהול, זכויות חתימה וקבלת החלטות",
                "מניעת תחרות ועיסוק נוסף",
                "העברת זכויות וזכות סירוב ראשונה",
                "מנגנון היפרדות (BMBY / הערכת שווי)",
                "פירוק השותפות וחלוקת נכסים",
                "יישוב סכסוכים",
            ),
            legal_notes=(
                "יש להסדיר מראש מבוי סתום בקבלת החלטות, אחרת כל צד עלול להיתקע",
                "חובות השותפות עשויות להטיל אחריות אישית על השותפים",
                "מנגנון היפרדות ברור הוא הסעיף שמונע את רוב הסכסוכים בפועל",
            ),
            search_hints=("פקודת השותפויות", "חובת אמון", "פירוק שותפות"),
        ),
        LegalTemplate(
            key="nda",
            name="הסכם סודיות (NDA)",
            description="שמירה על מידע חסוי בין צדדים",
            category="contract",
            icon="lock",
            fields=(
                *_party_fields("מוסר המידע", "מקבל המידע"),
                TemplateField(
                    "mutual", "הדדי (שני הצדדים מוסרים מידע)", FieldType.BOOLEAN
                ),
                TemplateField("purpose", "מטרת מסירת המידע", FieldType.MULTILINE, True),
                TemplateField(
                    "confidential_scope", "סוגי המידע החסוי", FieldType.MULTILINE
                ),
                TemplateField("duration_years", "תקופת הסודיות (שנים)", FieldType.NUMBER),
                TemplateField("penalty", "פיצוי מוסכם בגין הפרה", FieldType.CURRENCY),
                _JURISDICTION,
                _SIGN_DATE,
                _EXTRA_TERMS,
            ),
            required_sections=(
                "הגדרת מידע סודי",
                "חריגים לחובת הסודיות",
                "מטרת השימוש המותרת",
                "חובות שמירה ואבטחת מידע",
                "השבה או השמדה של המידע",
                "תקופת ההסכם ותוקף מתמשך",
                "תרופות, צווי מניעה ופיצוי מוסכם",
            ),
            legal_notes=(
                "יש להחריג מידע שהיה ידוע קודם, מידע פומבי ומידע שנדרש בצו שיפוטי",
                "פיצוי מוסכם צריך להיות פרופורציונלי לנזק הצפוי",
                "כאשר המידע כולל מידע אישי, חלות גם חובות מכוח דיני הגנת הפרטיות",
            ),
            search_hints=("סוד מסחרי", "עוולות מסחריות", "הגנת הפרטיות"),
        ),
        LegalTemplate(
            key="service",
            name="הסכם שירות",
            description="מתן שירותים מקצועיים על ידי נותן שירות עצמאי",
            category="contract",
            icon="engineering",
            fields=(
                *_party_fields("מזמין השירות", "נותן השירות"),
                TemplateField("services", "תיאור השירותים", FieldType.MULTILINE, True),
                TemplateField("deliverables", "תוצרים ואבני דרך", FieldType.MULTILINE),
                TemplateField("fee", "התמורה", FieldType.CURRENCY, True),
                TemplateField(
                    "payment_terms",
                    "תנאי תשלום",
                    FieldType.SELECT,
                    options=("שוטף+30", "שוטף+60", "מקדמה ויתרה", "לפי אבני דרך", "חודשי"),
                ),
                TemplateField("start_date", "תחילת ההתקשרות", FieldType.DATE),
                TemplateField("term", "משך ההתקשרות"),
                TemplateField("ip_ownership", "בעלות בקניין רוחני", FieldType.MULTILINE),
                _JURISDICTION,
                _SIGN_DATE,
                _EXTRA_TERMS,
            ),
            required_sections=(
                "היקף השירותים ותוצרים",
                "לוחות זמנים ואבני דרך",
                "תמורה, מע״מ, חשבוניות ומועדי תשלום",
                "היעדר יחסי עובד-מעסיק",
                "קניין רוחני וזכויות שימוש",
                "סודיות",
                "אחריות, שיפוי והגבלת אחריות",
                "סיום ההתקשרות והשלכותיו",
            ),
            legal_notes=(
                "יש לנסח את סעיף היעדר יחסי עובד-מעסיק בזהירות — בית הדין בוחן את "
                "מהות היחסים בפועל ולא את כותרת ההסכם",
                "יש להבהיר האם התמורה כוללת מע״מ",
                "בעלות בקניין רוחני אינה עוברת אוטומטית ויש להסדירה במפורש",
            ),
            search_hints=("יחסי עובד מעסיק", "קבלן עצמאי", "קניין רוחני"),
        ),
        LegalTemplate(
            key="sale",
            name="הסכם מכר",
            description="מכירת נכס או ציוד בין מוכר לקונה",
            category="contract",
            icon="sell",
            fields=(
                *_party_fields("המוכר", "הקונה"),
                TemplateField("asset_description", "תיאור הנכס הנמכר", FieldType.MULTILINE, True),
                TemplateField("price", "התמורה", FieldType.CURRENCY, True),
                TemplateField("payment_schedule", "לוח תשלומים", FieldType.MULTILINE),
                TemplateField("delivery_date", "מועד מסירה", FieldType.DATE),
                TemplateField("warranty", "אחריות ותקופתה", FieldType.MULTILINE),
                TemplateField("as_is", "נמכר במצבו AS-IS", FieldType.BOOLEAN),
                _JURISDICTION,
                _SIGN_DATE,
                _EXTRA_TERMS,
            ),
            required_sections=(
                "תיאור הממכר והצהרות המוכר",
                "התמורה ולוח תשלומים",
                "מועד ואופן המסירה",
                "העברת בעלות וסיכון",
                "אחריות, אי-התאמה ובדיקת הממכר",
                "הפרה, ביטול ותרופות",
            ),
            legal_notes=(
                "לקונה עומדת חובת בדיקה, אך אין בכך כדי לפטור את המוכר מגילוי מום שידע עליו",
                "עסקה במקרקעין טעונה מסמך בכתב וכרוכה בהיבטי מס נפרדים — נדרש ליווי עורך דין",
                "תניית AS-IS אינה חוסמת טענת הטעיה או אי-גילוי בחוסר תום לב",
            ),
            search_hints=("חוק המכר", "אי התאמה", "תרופות בשל הפרת חוזה"),
        ),
        LegalTemplate(
            key="investment",
            name="הסכם השקעה",
            description="השקעה בחברה בתמורה להקצאת מניות",
            category="contract",
            icon="trending_up",
            fields=(
                *_party_fields("החברה", "המשקיע"),
                TemplateField("company_number", "מספר חברה (ח.פ.)"),
                TemplateField("investment_amount", "סכום ההשקעה", FieldType.CURRENCY, True),
                TemplateField("valuation", "שווי החברה לפני ההשקעה", FieldType.CURRENCY),
                TemplateField("equity_percent", "אחוז החזקה", FieldType.NUMBER),
                TemplateField(
                    "instrument",
                    "מכשיר ההשקעה",
                    FieldType.SELECT,
                    options=("מניות רגילות", "מניות בכורה", "SAFE", "הלוואה המירה"),
                ),
                TemplateField("milestones", "אבני דרך לשחרור כספים", FieldType.MULTILINE),
                TemplateField("board_seat", "זכות למינוי דירקטור", FieldType.BOOLEAN),
                _JURISDICTION,
                _SIGN_DATE,
                _EXTRA_TERMS,
            ),
            required_sections=(
                "מבנה ההשקעה והקצאת המניות",
                "שווי, אחזקות ודילול",
                "מצגי החברה והמייסדים",
                "תנאים מתלים והעברת הכספים",
                "זכויות המשקיע: מידע, וטו, דירקטור",
                "זכויות העדפה, Tag Along ו-Drag Along",
                "אירוע אקזיט וחלוקת תמורה",
                "התחייבות המייסדים והבשלה (Vesting)",
            ),
            legal_notes=(
                "הסכם השקעה משפיע על תקנון החברה ועשוי לחייב תיקונו",
                "יש לבחון היבטי מס לפני חתימה, הן ברמת החברה והן ברמת המשקיע",
                "מסמך זה הוא טיוטה לדיון בלבד ומחייב ליווי של עורך דין מסחרי",
            ),
            search_hints=("חוק החברות", "הקצאת מניות", "זכויות מיעוט"),
        ),
        LegalTemplate(
            key="loan",
            name="הסכם הלוואה",
            description="הלוואה פרטית בין מלווה ללווה",
            category="contract",
            icon="payments",
            fields=(
                *_party_fields("המלווה", "הלווה"),
                TemplateField("amount", "סכום ההלוואה", FieldType.CURRENCY, True),
                TemplateField("interest_rate", "שיעור ריבית שנתית (%)", FieldType.NUMBER),
                TemplateField(
                    "linkage",
                    "הצמדה",
                    FieldType.SELECT,
                    options=("ללא הצמדה", "מדד המחירים לצרכן", "דולר", "אחר"),
                ),
                TemplateField("repayment_schedule", "לוח סילוקין", FieldType.MULTILINE, True),
                TemplateField("first_payment", "מועד תשלום ראשון", FieldType.DATE),
                TemplateField("collateral", "בטוחות", FieldType.MULTILINE),
                TemplateField("guarantor", "ערב"),
                _JURISDICTION,
                _SIGN_DATE,
                _EXTRA_TERMS,
            ),
            required_sections=(
                "סכום ההלוואה ומועד העמדתה",
                "ריבית, הצמדה וריבית פיגורים",
                "לוח סילוקין ופירעון מוקדם",
                "בטוחות וערבויות",
                "עילות להעמדה לפירעון מיידי",
                "הוצאות גבייה",
            ),
            legal_notes=(
                "חוק אשראי הוגן מגביל את שיעור הריבית המרבי בהלוואות חוץ-בנקאיות "
                "וקובע חובות גילוי; חריגה עלולה לבטל את הריבית",
                "יש למסור ללווה גילוי ברור של העלות הכוללת של האשראי",
                "ערבות מחייבת מסירת מידע לערב לפני החתימה",
            ),
            search_hints=("חוק אשראי הוגן", "ריבית והצמדה", "חוק הערבות"),
        ),
        LegalTemplate(
            key="contractor",
            name="הסכם קבלן",
            description="ביצוע עבודות בנייה או שיפוץ",
            category="contract",
            icon="construction",
            fields=(
                *_party_fields("המזמין", "הקבלן"),
                TemplateField("site_address", "כתובת האתר", required=True),
                TemplateField("works_description", "תיאור העבודות", FieldType.MULTILINE, True),
                TemplateField("price", "התמורה הכוללת", FieldType.CURRENCY, True),
                TemplateField("payment_milestones", "לוח תשלומים לפי אבני דרך", FieldType.MULTILINE),
                TemplateField("start_date", "מועד תחילת עבודות", FieldType.DATE),
                TemplateField("completion_date", "מועד סיום", FieldType.DATE),
                TemplateField("delay_penalty", "פיצוי בגין איחור ליום", FieldType.CURRENCY),
                TemplateField("warranty_months", "תקופת בדק (חודשים)", FieldType.NUMBER),
                _JURISDICTION,
                _SIGN_DATE,
                _EXTRA_TERMS,
            ),
            required_sections=(
                "תיאור העבודות, מפרט וכתב כמויות",
                "לוח זמנים ואבני דרך",
                "התמורה, חשבונות חלקיים וחשבון סופי",
                "שינויים ותוספות (Change Orders)",
                "רישיונות, היתרים ובטיחות",
                "ביטוחים וערבות ביצוע",
                "מסירה, ליקויים ותקופת בדק",
                "איחורים ופיצוי מוסכם",
                "יישוב סכסוכים",
            ),
            legal_notes=(
                "יש להסדיר במפורש מנגנון לאישור שינויים ותוספות — זהו מקור הסכסוך "
                "הנפוץ ביותר בפרויקטי בנייה",
                "יש לוודא כיסוי ביטוחי לצד שלישי ולעבודות קבלניות",
                "ערבות ביצוע וערבות בדק מגנות על המזמין לאחר המסירה",
            ),
            search_hints=("חוק המכר דירות", "ליקויי בנייה", "תקופת בדק"),
        ),
    )
}


# --------------------------------------------------------------------- letters
_RECIPIENT_FIELDS = (
    TemplateField("sender_name", "שם השולח", required=True),
    TemplateField("sender_id", "ת.ז. / ח.פ. של השולח"),
    TemplateField("sender_address", "כתובת השולח"),
    TemplateField("sender_contact", "טלפון / דוא״ל ליצירת קשר"),
    TemplateField("recipient_name", "שם הנמען", required=True),
    TemplateField("recipient_address", "כתובת הנמען"),
)

LETTER_TEMPLATES: dict[str, LegalTemplate] = {
    template.key: template
    for template in (
        LegalTemplate(
            key="warning",
            name="מכתב התראה",
            description="התראה לפני נקיטת הליכים משפטיים",
            category="letter",
            icon="warning",
            fields=(
                *_RECIPIENT_FIELDS,
                TemplateField("subject", "נושא ההתראה", required=True),
                TemplateField("facts", "תיאור העובדות", FieldType.MULTILINE, True),
                TemplateField("demand", "הדרישה", FieldType.MULTILINE, True),
                TemplateField("deadline_days", "מספר ימים למענה", FieldType.NUMBER),
                TemplateField("amount", "סכום הנדרש (אם רלוונטי)", FieldType.CURRENCY),
            ),
            required_sections=(
                "פירוט העובדות והרקע",
                "הבסיס לטענה",
                "הדרישה המפורשת",
                "מועד קצוב למענה",
                "הבהרה על נקיטת הליכים בהיעדר מענה",
                "שמירת זכויות",
            ),
            legal_notes=(
                "מכתב התראה נדרש לעיתים כתנאי מקדים או משפיע על פסיקת הוצאות",
                "יש לשלוח באופן שניתן להוכיח מסירה ולשמור אישור",
                "אין לכלול איומים החורגים מנקיטת הליכים משפטיים לגיטימיים",
            ),
        ),
        LegalTemplate(
            key="demand",
            name="מכתב דרישה",
            description="דרישת תשלום או ביצוע התחייבות",
            category="letter",
            icon="request_quote",
            fields=(
                *_RECIPIENT_FIELDS,
                TemplateField("subject", "נושא הדרישה", required=True),
                TemplateField("basis", "מקור החוב או ההתחייבות", FieldType.MULTILINE, True),
                TemplateField("amount", "הסכום הנדרש", FieldType.CURRENCY),
                TemplateField("due_date", "מועד לתשלום", FieldType.DATE),
                TemplateField("payment_details", "פרטי תשלום"),
            ),
            required_sections=(
                "מקור החוב או ההתחייבות",
                "פירוט הסכום וחישובו",
                "מועד לתשלום ואופן התשלום",
                "השלכות אי-תשלום",
                "שמירת זכויות",
            ),
            legal_notes=(
                "יש לצרף אסמכתאות: חשבוניות, הסכם, התכתבות",
                "ריבית והצמדה נצברות ממועד היווצרות החוב ויש לציין זאת",
            ),
        ),
        LegalTemplate(
            key="municipality",
            name="מכתב לעירייה",
            description="פנייה או השגה מול רשות מקומית",
            category="letter",
            icon="location_city",
            fields=(
                *_RECIPIENT_FIELDS,
                TemplateField("municipality", "שם הרשות המקומית", required=True),
                TemplateField("department", "מחלקה / אגף"),
                TemplateField("subject", "נושא הפנייה", required=True),
                TemplateField("reference_number", "מספר אסמכתה / דרישה"),
                TemplateField("details", "פירוט הפנייה", FieldType.MULTILINE, True),
                TemplateField("request", "מה מבוקש", FieldType.MULTILINE, True),
            ),
            required_sections=(
                "פרטי הפונה והנכס / התיק",
                "מספרי אסמכתה",
                "פירוט העובדות",
                "הבקשה או ההשגה",
                "בקשה למענה בכתב תוך זמן סביר",
            ),
            legal_notes=(
                "להשגות בענייני ארנונה ובענייני תכנון ובנייה קיימים מועדים קצובים "
                "וקצרים — יש לבדוק את המועד הרלוונטי לפני הפנייה",
                "ניתן להסתמך על חובת ההנמקה של רשות מנהלית",
            ),
        ),
        LegalTemplate(
            key="court",
            name="מכתב לבית משפט",
            description="פנייה מנהלית למזכירות בית המשפט",
            category="letter",
            icon="gavel",
            fields=(
                *_RECIPIENT_FIELDS,
                TemplateField("court_name", "שם בית המשפט", required=True),
                TemplateField("case_number", "מספר ההליך", required=True),
                TemplateField("parties", "שמות הצדדים"),
                TemplateField("subject", "נושא הפנייה", required=True),
                TemplateField("details", "פירוט", FieldType.MULTILINE, True),
            ),
            required_sections=(
                "כותרת עם שם בית המשפט ומספר ההליך",
                "זהות הפונה ומעמדו בהליך",
                "מהות הפנייה",
                "הבקשה המבוקשת",
            ),
            legal_notes=(
                "פנייה לבית משפט כפופה לתקנות סדר הדין ולכללי המצאה — יש להעביר "
                "העתק לכל בעלי הדין",
                "מסמך זה אינו מהווה כתב טענות ואינו תחליף להגשה כדין",
            ),
        ),
        LegalTemplate(
            key="lawyer",
            name="מכתב לעורך דין",
            description="פנייה מסודרת לעורך דין לקראת טיפול",
            category="letter",
            icon="badge",
            fields=(
                *_RECIPIENT_FIELDS,
                TemplateField("matter", "נושא הפנייה", required=True),
                TemplateField("background", "רקע עובדתי", FieldType.MULTILINE, True),
                TemplateField("documents", "מסמכים שברשותך", FieldType.MULTILINE),
                TemplateField("questions", "שאלות שברצונך לשאול", FieldType.MULTILINE),
                TemplateField("urgency", "דחיפות ומועדים קרובים"),
            ),
            required_sections=(
                "תיאור העניין בקצרה",
                "רקע עובדתי לפי סדר כרונולוגי",
                "מסמכים מצורפים",
                "השאלות והבקשות",
                "מועדים קריטיים",
            ),
            legal_notes=(
                "פירוט כרונולוגי מדויק חוסך זמן ועלויות בפגישה הראשונה",
                "יש לציין כל מועד קצוב ידוע כדי שלא יוחמץ",
            ),
        ),
        LegalTemplate(
            key="appeal",
            name="ערעור",
            description="טיוטת הודעת ערעור על החלטה",
            category="letter",
            icon="undo",
            fields=(
                *_RECIPIENT_FIELDS,
                TemplateField("court_name", "הערכאה שאליה מוגש הערעור", required=True),
                TemplateField("original_decision", "ההחלטה המעורערת", required=True),
                TemplateField("decision_date", "תאריך ההחלטה", FieldType.DATE, True),
                TemplateField("case_number", "מספר ההליך"),
                TemplateField("grounds", "עילות הערעור", FieldType.MULTILINE, True),
                TemplateField("relief", "הסעד המבוקש", FieldType.MULTILINE, True),
            ),
            required_sections=(
                "פרטי ההחלטה המעורערת",
                "העובדות הרלוונטיות",
                "עילות הערעור, מנומקות ומופרדות",
                "הסעד המבוקש",
                "נספחים",
            ),
            legal_notes=(
                "לערעור קיים מועד קצוב וקצר מיום ההחלטה או מיום ההמצאה — איחור "
                "מחייב בקשת הארכה ועלול לחסום את הערעור לחלוטין",
                "ערעור מתמקד בטעות שבחוק או בטעות מהותית ולא בחזרה על ההליך",
                "טיוטה זו מחייבת בדיקה של עורך דין לפני הגשה",
            ),
        ),
        LegalTemplate(
            key="request",
            name="בקשה",
            description="בקשה מנומקת לגוף או לרשות",
            category="letter",
            icon="description",
            fields=(
                *_RECIPIENT_FIELDS,
                TemplateField("authority", "הגוף אליו מופנית הבקשה", required=True),
                TemplateField("subject", "נושא הבקשה", required=True),
                TemplateField("grounds", "נימוקי הבקשה", FieldType.MULTILINE, True),
                TemplateField("relief", "מה מבוקש", FieldType.MULTILINE, True),
                TemplateField("attachments", "מסמכים מצורפים", FieldType.MULTILINE),
            ),
            required_sections=(
                "זהות המבקש",
                "נושא הבקשה",
                "נימוקים",
                "הסעד המבוקש",
                "רשימת נספחים",
            ),
        ),
        LegalTemplate(
            key="affidavit",
            name="תצהיר",
            description="טיוטת תצהיר בכתב לאימות עובדות",
            category="letter",
            icon="fact_check",
            fields=(
                *_RECIPIENT_FIELDS,
                TemplateField("declarant_name", "שם המצהיר", required=True),
                TemplateField("declarant_id", "ת.ז. המצהיר", required=True),
                TemplateField("purpose", "מטרת התצהיר", required=True),
                TemplateField("facts", "העובדות המוצהרות", FieldType.MULTILINE, True),
                TemplateField("case_number", "מספר הליך (אם קיים)"),
            ),
            required_sections=(
                "כותרת התצהיר",
                "פרטי המצהיר",
                "הצהרת אמת פותחת",
                "עובדות ממוספרות, כל עובדה בסעיף נפרד",
                "הצהרה מסכמת",
                "מקום לאימות חתימה בפני עורך דין",
            ),
            legal_notes=(
                "תצהיר טעון אימות חתימה בפני עורך דין כדי שיהיה בעל תוקף ראייתי",
                "הצהרת שקר בתצהיר היא עבירה פלילית",
                "יש להצהיר רק על עובדות בידיעה אישית ולסמן מפורשות מה נאמר "
                "על סמך מידע ואמונה",
            ),
        ),
        LegalTemplate(
            key="defense",
            name="כתב הגנה",
            description="טיוטת כתב הגנה בתביעה אזרחית",
            category="letter",
            icon="shield",
            fields=(
                *_RECIPIENT_FIELDS,
                TemplateField("court_name", "בית המשפט", required=True),
                TemplateField("case_number", "מספר התיק", required=True),
                TemplateField("plaintiff", "שם התובע", required=True),
                TemplateField("claim_summary", "תמצית התביעה", FieldType.MULTILINE, True),
                TemplateField("defense_arguments", "טענות ההגנה", FieldType.MULTILINE, True),
                TemplateField("counterclaim", "האם קיימת תביעה שכנגד", FieldType.BOOLEAN),
            ),
            required_sections=(
                "כותרת ההליך והצדדים",
                "טענות מקדמיות",
                "התייחסות סעיף-סעיף לכתב התביעה",
                "גרסת הנתבע",
                "טענות משפטיות",
                "סיכום והסעד המבוקש",
            ),
            legal_notes=(
                "לכתב הגנה קיים מועד קצוב מיום ההמצאה; איחור עלול להוביל לפסק דין "
                "בהיעדר הגנה",
                "עובדה בכתב התביעה שלא הוכחשה במפורש עלולה להיחשב כמוכחת",
                "טענות מקדמיות יש להעלות בהזדמנות הראשונה",
                "טיוטה זו אינה תחליף לייצוג ומחייבת בדיקה של עורך דין לפני הגשה",
            ),
        ),
        LegalTemplate(
            key="claim",
            name="כתב תביעה (טיוטה)",
            description="טיוטה ראשונית לכתב תביעה אזרחית",
            category="letter",
            icon="article",
            fields=(
                *_RECIPIENT_FIELDS,
                TemplateField("court_name", "בית המשפט המוסמך", required=True),
                TemplateField("defendant", "שם הנתבע", required=True),
                TemplateField("defendant_address", "כתובת הנתבע"),
                TemplateField("facts", "העובדות", FieldType.MULTILINE, True),
                TemplateField("cause_of_action", "עילת התביעה", FieldType.MULTILINE, True),
                TemplateField("amount", "סכום התביעה", FieldType.CURRENCY),
                TemplateField("relief", "הסעדים המבוקשים", FieldType.MULTILINE, True),
            ),
            required_sections=(
                "כותרת ההליך והצדדים",
                "סמכות עניינית ומקומית",
                "העובדות, ממוספרות",
                "עילות התביעה",
                "הנזק וחישובו",
                "הסעדים המבוקשים",
                "רשימת נספחים",
            ),
            legal_notes=(
                "סמכות עניינית נקבעת לפי סכום התביעה וסוג העניין; הגשה לערכאה "
                "שאינה מוסמכת גוררת עיכוב ועלויות",
                "תקופת ההתיישנות עלולה לחסום תביעה — יש לבדוק אותה מוקדם",
                "אגרת בית משפט מחושבת לפי סכום התביעה",
                "טיוטה זו אינה כתב טענות מוגמר ומחייבת עריכה של עורך דין",
            ),
        ),
    )
}

ALL_TEMPLATES: dict[str, LegalTemplate] = {**CONTRACT_TEMPLATES, **LETTER_TEMPLATES}


def get_contract_template(key: str) -> LegalTemplate | None:
    return CONTRACT_TEMPLATES.get(key)


def get_letter_template(key: str) -> LegalTemplate | None:
    return LETTER_TEMPLATES.get(key)


def list_templates(category: str | None = None) -> list[LegalTemplate]:
    """List templates, optionally filtered by ``contract``/``letter``."""
    templates = ALL_TEMPLATES.values()
    if category:
        return [t for t in templates if t.category == category]
    return list(templates)

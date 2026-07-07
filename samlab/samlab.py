#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
מבריק לאב (SamLab) — תוכנת מעבדה ל-Windows לתיקון והחייאת מכשירי Samsung.

ארכיטקטורה: התוכנה היא ממשק (orchestrator) מודרני בעברית מעל מנועים
פתוחים ובדוקים, במקום לממש מחדש פרוטוקולים שבירים:
  • הורדת קושחה רשמית  → samloader  (crypto נכון של Samsung FUS)
  • פלאש / זיהוי / PIT   → Heimdall   (מנוע Odin/LOKE פתוח ובטוח)
  • מידע ומעבר מצבים      → adb        (platform-tools)

מה שהתוכנה *לא* עושה, במכוון: עקיפת FRP / הסרת חשבון Google או Samsung.
אלה מנגנוני הגנה מפני גניבה; המסלול הנכון ללקוח עם בעלות אמיתית הוא
שחזור חשבון רשמי או הוכחת רכישה מול Samsung.

תלויות: Python 3.10+ (Tkinter מובנה). מנועים חיצוניים (heimdall.exe,
adb.exe, samloader) מונחים ב-tools/ או ב-PATH — ראה README.
"""

import os
import sys
import json
import shutil
import threading
import subprocess
import queue
import tarfile
import glob
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

APP_NAME = "מבריק לאב · SamLab"
APP_DIR = os.path.dirname(os.path.abspath(__file__))
TOOLS_DIR = os.path.join(APP_DIR, "tools")
CONFIG_PATH = os.path.join(APP_DIR, "samlab_config.json")

# צבעים (ערכת נושא כהה תואמת לשאר הכלים)
BG = "#0a0e14"
CARD = "#111826"
CARD2 = "#172234"
FG = "#eef4fb"
MUTED = "#8a9bb2"
CYAN = "#38bdf8"
OK = "#34d399"
WARN = "#fbbf24"
BAD = "#fb5f7d"
BORDER = "#24344b"

# קודי CSC נפוצים (הקוד הראשון בכל שורה הוא מה שנשלח). ישראל בראש.
# הרשימה ניתנת לעריכה חופשית — אפשר להקליד כל קוד ידנית.
CSC_CODES = [
    "ILO — ישראל (לא מותג)",
    "CEL — ישראל (סלקום)",
    "PCL — ישראל (פלאפון)",
    "PTR — ישראל (פרטנר / אורנג')",
    "XSG — איחוד האמירויות",
    "XAA — ארה\"ב (לא נעול)",
    "BTU — בריטניה",
    "XEF — צרפת",
    "DBT — גרמניה",
    "ITV — איטליה",
    "PHE — ספרד",
    "XEO — פולין",
    "NEE — סקנדינביה",
    "SER — רוסיה",
    "TUR — טורקיה",
    "INS — הודו",
    "XSA — אוסטרליה",
    "ZTO — ברזיל",
    "TGY — הונג קונג",
    "KOO — קוריאה",
    "CHC — סין",
]

# דגמים נפוצים לנוחות (ניתן להקליד כל דגם ידנית).
COMMON_MODELS = [
    "SM-S928B — Galaxy S24 Ultra",
    "SM-S921B — Galaxy S24",
    "SM-S918B — Galaxy S23 Ultra",
    "SM-S911B — Galaxy S23",
    "SM-G998B — Galaxy S21 Ultra",
    "SM-G991B — Galaxy S21",
    "SM-A546B — Galaxy A54 5G",
    "SM-A356B — Galaxy A35 5G",
    "SM-A155F — Galaxy A15",
    "SM-A047F — Galaxy A04s",
    "SM-F946B — Galaxy Z Fold5",
    "SM-F731B — Galaxy Z Flip5",
]


def code_only(value):
    """מחלץ את הקוד/דגם מתוך פריט ברשימה בפורמט 'CODE — תיאור'."""
    if not value:
        return ""
    head = value.split("—")[0].strip()      # החלק שלפני המקף
    tokens = head.split()
    return tokens[0].strip().upper() if tokens else ""


# ---------------------------------------------------------------------------
# איתור כלים חיצוניים
# ---------------------------------------------------------------------------
def find_tool(names):
    """מחזיר נתיב מלא לכלי: קודם ב-tools/, אחר כך ב-PATH."""
    for n in names:
        p = os.path.join(TOOLS_DIR, n)
        if os.path.isfile(p):
            return p
    for n in names:
        p = shutil.which(n)
        if p:
            return p
    return None


def load_config():
    cfg = {"heimdall": "", "adb": "", "samloader": "", "out_dir": os.path.join(APP_DIR, "firmware")}
    if os.path.isfile(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                cfg.update(json.load(f))
        except Exception:
            pass
    # השלמה אוטומטית
    if not cfg["heimdall"]:
        cfg["heimdall"] = find_tool(["heimdall.exe", "heimdall"]) or ""
    if not cfg["adb"]:
        cfg["adb"] = find_tool(["adb.exe", "adb"]) or ""
    if not cfg["samloader"]:
        # samloader מורץ כמודול פייתון אם מותקן ב-pip
        cfg["samloader"] = find_tool(["samloader.exe", "samloader"]) or ""
    return cfg


def save_config(cfg):
    try:
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


# ---------------------------------------------------------------------------
# הרצת פקודות ברקע עם הזרמת פלט ללוג
# ---------------------------------------------------------------------------
class Runner:
    """מריץ תהליכי משנה בשרשור נפרד ומזרים שורות פלט לתור."""

    def __init__(self, log_queue):
        self.q = log_queue
        self.proc = None
        self.busy = False

    def run(self, argv, cwd=None, on_done=None):
        if self.busy:
            self.q.put(("warn", "פעולה אחרת עדיין רצה — המתן לסיומה."))
            return
        self.busy = True
        t = threading.Thread(target=self._worker, args=(argv, cwd, on_done), daemon=True)
        t.start()

    def _worker(self, argv, cwd, on_done):
        self.q.put(("cmd", "> " + " ".join(_quote(a) for a in argv)))
        try:
            creation = 0
            if os.name == "nt":
                creation = getattr(subprocess, "CREATE_NO_WINDOW", 0)
            self.proc = subprocess.Popen(
                argv, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, bufsize=1, encoding="utf-8", errors="replace",
                creationflags=creation,
            )
            for line in self.proc.stdout:
                self.q.put(("out", line.rstrip("\n")))
            code = self.proc.wait()
            if code == 0:
                self.q.put(("ok", "✓ הסתיים בהצלחה (קוד 0)."))
            else:
                self.q.put(("err", f"✗ הסתיים עם קוד שגיאה {code}."))
            if on_done:
                on_done(code)
        except FileNotFoundError:
            self.q.put(("err", f"✗ לא נמצא הכלי: {argv[0]} — בדוק את הנתיבים בטאב הגדרות."))
        except Exception as e:
            self.q.put(("err", f"✗ שגיאה: {e}"))
        finally:
            self.busy = False
            self.proc = None

    def stop(self):
        if self.proc:
            try:
                self.proc.terminate()
                self.q.put(("warn", "הפעולה בוטלה."))
            except Exception:
                pass


def _quote(s):
    return f'"{s}"' if " " in str(s) else str(s)


# ---------------------------------------------------------------------------
# ניתוח פלט PIT של Heimdall למיפוי מחיצה → שם קובץ
# ---------------------------------------------------------------------------
def parse_pit(text):
    """מחזיר רשימת dict: {'partition': שם, 'filename': שם קובץ פלאש}."""
    parts = []
    cur = {}
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("--- Entry"):
            if cur.get("partition"):
                parts.append(cur)
            cur = {}
        elif line.startswith("Partition Name:"):
            cur["partition"] = line.split(":", 1)[1].strip()
        elif line.startswith("Flash Filename:"):
            cur["filename"] = line.split(":", 1)[1].strip()
    if cur.get("partition"):
        parts.append(cur)
    return [p for p in parts if p.get("filename")]


# ---------------------------------------------------------------------------
# הממשק הגרפי
# ---------------------------------------------------------------------------
class SamLabApp:
    def __init__(self, root):
        self.root = root
        self.cfg = load_config()
        self.q = queue.Queue()
        self.runner = Runner(self.q)
        self.flash_files = {"BL": "", "AP": "", "CP": "", "CSC": "", "USERDATA": ""}
        self.pit_map = []

        root.title(APP_NAME)
        root.configure(bg=BG)
        root.geometry("940x680")
        root.minsize(820, 600)
        self._style()
        self._build()
        self.root.after(80, self._drain_log)

    # ---- עיצוב ----
    def _style(self):
        st = ttk.Style()
        try:
            st.theme_use("clam")
        except Exception:
            pass
        st.configure(".", background=BG, foreground=FG, fieldbackground=CARD2, font=("Segoe UI", 10))
        st.configure("TNotebook", background=BG, borderwidth=0)
        st.configure("TNotebook.Tab", background=CARD, foreground=MUTED, padding=(16, 8), borderwidth=0)
        st.map("TNotebook.Tab", background=[("selected", CARD2)], foreground=[("selected", CYAN)])
        st.configure("TFrame", background=BG)
        st.configure("Card.TFrame", background=CARD)
        st.configure("TLabel", background=BG, foreground=FG)
        st.configure("Card.TLabel", background=CARD, foreground=FG)
        st.configure("Muted.TLabel", background=CARD, foreground=MUTED)
        st.configure("H.TLabel", background=CARD, foreground=FG, font=("Segoe UI", 12, "bold"))
        st.configure("TButton", background=CARD2, foreground=FG, borderwidth=1, focusthickness=0, padding=(12, 7))
        st.map("TButton", background=[("active", "#20304a")])
        st.configure("Accent.TButton", background=CYAN, foreground="#04263a", font=("Segoe UI", 10, "bold"))
        st.map("Accent.TButton", background=[("active", "#5cc9fb")])
        st.configure("Danger.TButton", background="#3a1720", foreground="#ffd7de")
        st.map("Danger.TButton", background=[("active", "#4a1c28")])
        st.configure("TEntry", fieldbackground=CARD2, foreground=FG, insertcolor=FG)
        st.configure("TCombobox", fieldbackground=CARD2, foreground=FG)

    def _build(self):
        header = tk.Frame(self.root, bg=BG)
        header.pack(fill="x", padx=16, pady=(12, 6))
        tk.Label(header, text="🔧 מבריק לאב", bg=BG, fg=FG, font=("Segoe UI", 16, "bold")).pack(side="right")
        tk.Label(header, text="תיקון והחייאת Samsung · הורדה · פלאש · מידע", bg=BG, fg=MUTED,
                 font=("Segoe UI", 10)).pack(side="right", padx=12)

        nb = ttk.Notebook(self.root)
        nb.pack(fill="both", expand=True, padx=16, pady=6)
        self.tab_dl = ttk.Frame(nb)
        self.tab_flash = ttk.Frame(nb)
        self.tab_dev = ttk.Frame(nb)
        self.tab_cfg = ttk.Frame(nb)
        nb.add(self.tab_dl, text="⬇  הורדת קושחה")
        nb.add(self.tab_flash, text="💾  פלאש")
        nb.add(self.tab_dev, text="🩺  מכשיר ומצבים")
        nb.add(self.tab_cfg, text="⚙  הגדרות")
        self._build_download(self.tab_dl)
        self._build_flash(self.tab_flash)
        self._build_device(self.tab_dev)
        self._build_config(self.tab_cfg)

        # לוג משותף בתחתית
        logwrap = tk.Frame(self.root, bg=BG)
        logwrap.pack(fill="both", expand=False, padx=16, pady=(0, 12))
        bar = tk.Frame(logwrap, bg=BG)
        bar.pack(fill="x")
        tk.Label(bar, text="יומן פעולות", bg=BG, fg=MUTED, font=("Segoe UI", 9, "bold")).pack(side="right")
        tk.Button(bar, text="נקה", command=self._clear_log, bg=CARD, fg=FG, bd=0,
                  activebackground=CARD2, font=("Segoe UI", 9)).pack(side="left")
        tk.Button(bar, text="עצור", command=self.runner.stop, bg="#3a1720", fg="#ffd7de", bd=0,
                  activebackground="#4a1c28", font=("Segoe UI", 9)).pack(side="left", padx=6)
        self.log = tk.Text(logwrap, height=11, bg="#060a10", fg=FG, insertbackground=FG,
                           bd=0, font=("Consolas", 9), wrap="word")
        self.log.pack(fill="both", expand=True, pady=(4, 0))
        for tag, col in (("out", MUTED), ("cmd", CYAN), ("ok", OK), ("err", BAD), ("warn", WARN), ("info", FG)):
            self.log.tag_configure(tag, foreground=col)
        self._log("info", "מוכן. ודא שהכלים מוגדרים בטאב 'הגדרות'.")

    # ---- טאב: הורדת קושחה ----
    def _build_download(self, parent):
        c = self._card(parent)
        ttk.Label(c, text="הורדת קושחה רשמית מ-Samsung", style="H.TLabel").grid(row=0, column=0, columnspan=4, sticky="e", pady=(0, 4))
        ttk.Label(c, text="נעשה דרך samloader — שרתי FUS הרשמיים, כולל פענוח .enc4/.enc2 אוטומטי.",
                  style="Muted.TLabel").grid(row=1, column=0, columnspan=4, sticky="e", pady=(0, 12))

        ttk.Label(c, text="דגם (בחר או הקלד, למשל SM-G991B)", style="Card.TLabel").grid(row=2, column=3, sticky="e", pady=(8, 2))
        self.e_model = ttk.Combobox(c, width=34, values=COMMON_MODELS)
        self.e_model.grid(row=2, column=1, columnspan=2, sticky="ew", padx=(0, 6), pady=(8, 2))

        ttk.Label(c, text="אזור / CSC (בחר או הקלד)", style="Card.TLabel").grid(row=3, column=3, sticky="e", pady=(8, 2))
        self.e_region = ttk.Combobox(c, width=34, values=CSC_CODES)
        self.e_region.grid(row=3, column=1, columnspan=2, sticky="ew", padx=(0, 6), pady=(8, 2))

        ttk.Label(c, text="תיקיית יעד", style="Card.TLabel").grid(row=4, column=3, sticky="e", pady=(8, 2))
        self.e_out = ttk.Entry(c, width=48)
        self.e_out.insert(0, self.cfg.get("out_dir", ""))
        self.e_out.grid(row=5, column=1, columnspan=2, sticky="ew", padx=(0, 6))
        ttk.Button(c, text="בחר…", command=self._pick_out).grid(row=5, column=0, sticky="w")

        btns = ttk.Frame(c, style="Card.TFrame")
        btns.grid(row=6, column=0, columnspan=4, sticky="e", pady=(14, 0))
        ttk.Button(btns, text="🔎 בדוק גרסה עדכנית", command=self.act_checkupdate).pack(side="right", padx=4)
        ttk.Button(btns, text="⬇ הורד + פענח", style="Accent.TButton", command=self.act_download).pack(side="right", padx=4)
        c.columnconfigure(1, weight=1)

    # ---- טאב: פלאש ----
    def _build_flash(self, parent):
        c = self._card(parent)
        ttk.Label(c, text="פלאש קושחה (Heimdall / Odin)", style="H.TLabel").grid(row=0, column=0, columnspan=4, sticky="e", pady=(0, 4))
        ttk.Label(c, text="הכנס את המכשיר ל-Download Mode (Vol- + Vol+ בחיבור כבל, ואשר). דורש דרייבר — ראה README.",
                  style="Muted.TLabel").grid(row=1, column=0, columnspan=4, sticky="e", pady=(0, 12))

        top = ttk.Frame(c, style="Card.TFrame")
        top.grid(row=2, column=0, columnspan=4, sticky="e", pady=(0, 10))
        ttk.Button(top, text="🔌 זהה מכשיר", command=self.act_detect).pack(side="right", padx=4)
        ttk.Button(top, text="📄 קרא PIT", command=self.act_print_pit).pack(side="right", padx=4)

        # בוחרי קבצים BL/AP/CP/CSC/USERDATA
        self.flash_labels = {}
        row = 3
        for key, desc in (("BL", "Bootloader"), ("AP", "מערכת (AP)"), ("CP", "מודם (CP)"),
                          ("CSC", "CSC"), ("USERDATA", "USERDATA (רשות)")):
            ttk.Label(c, text=f"{key} — {desc}", style="Card.TLabel").grid(row=row, column=3, sticky="e", pady=2)
            lbl = ttk.Label(c, text="לא נבחר", style="Muted.TLabel")
            lbl.grid(row=row, column=1, columnspan=1, sticky="e", padx=6)
            ttk.Button(c, text="בחר .tar.md5…", command=lambda k=key: self._pick_flash(k)).grid(row=row, column=0, sticky="w")
            self.flash_labels[key] = lbl
            row += 1

        btns = ttk.Frame(c, style="Card.TFrame")
        btns.grid(row=row, column=0, columnspan=4, sticky="e", pady=(14, 0))
        ttk.Button(btns, text="🧩 הכן תוכנית פלאש", command=self.act_prepare_flash).pack(side="right", padx=4)
        ttk.Button(btns, text="💾 בצע פלאש", style="Danger.TButton", command=self.act_flash).pack(side="right", padx=4)
        c.columnconfigure(1, weight=1)

    # ---- טאב: מכשיר ומצבים ----
    def _build_device(self, parent):
        c = self._card(parent)
        ttk.Label(c, text="מידע ומעבר בין מצבים (ADB)", style="H.TLabel").grid(row=0, column=0, columnspan=3, sticky="e", pady=(0, 4))
        ttk.Label(c, text="עבור מכשיר דלוק עם ניפוי USB מופעל. שימושי לאתחל אל Download Mode לפני פלאש.",
                  style="Muted.TLabel").grid(row=1, column=0, columnspan=3, sticky="e", pady=(0, 12))

        grid = ttk.Frame(c, style="Card.TFrame")
        grid.grid(row=2, column=0, columnspan=3, sticky="e")
        ttk.Button(grid, text="📋 מידע מכשיר", command=self.act_adb_info).grid(row=0, column=0, padx=4, pady=4)
        ttk.Button(grid, text="⬇ אתחל ל-Download", command=lambda: self.act_adb_reboot("download")).grid(row=0, column=1, padx=4, pady=4)
        ttk.Button(grid, text="🛟 אתחל ל-Recovery", command=lambda: self.act_adb_reboot("recovery")).grid(row=0, column=2, padx=4, pady=4)
        ttk.Button(grid, text="🔁 אתחל ל-Bootloader", command=lambda: self.act_adb_reboot("bootloader")).grid(row=1, column=1, padx=4, pady=4)
        ttk.Button(grid, text="▶ אתחול רגיל", command=lambda: self.act_adb_reboot("")).grid(row=1, column=0, padx=4, pady=4)

    # ---- טאב: הגדרות ----
    def _build_config(self, parent):
        c = self._card(parent)
        ttk.Label(c, text="נתיבי כלים", style="H.TLabel").grid(row=0, column=0, columnspan=3, sticky="e", pady=(0, 4))
        ttk.Label(c, text="השאר ריק לאיתור אוטומטי מתוך tools/ או מ-PATH.",
                  style="Muted.TLabel").grid(row=1, column=0, columnspan=3, sticky="e", pady=(0, 12))
        self.cfg_entries = {}
        rows = [("heimdall", "heimdall.exe"), ("adb", "adb.exe"),
                ("samloader", "samloader (או השאר ריק לשימוש ב-python -m samloader)")]
        for i, (key, hint) in enumerate(rows, start=2):
            ttk.Label(c, text=hint, style="Card.TLabel").grid(row=i, column=2, sticky="e", pady=4)
            e = ttk.Entry(c, width=52)
            e.insert(0, self.cfg.get(key, ""))
            e.grid(row=i, column=1, sticky="ew", padx=6)
            ttk.Button(c, text="בחר…", command=lambda k=key, ent=e: self._pick_tool(ent)).grid(row=i, column=0, sticky="w")
            self.cfg_entries[key] = e
        ttk.Button(c, text="💾 שמור הגדרות", style="Accent.TButton", command=self.act_save_cfg).grid(
            row=99, column=0, columnspan=3, sticky="e", pady=(16, 0))
        ttk.Button(c, text="🔍 בדוק זמינות כלים", command=self.act_check_tools).grid(
            row=99, column=0, sticky="w", pady=(16, 0))
        c.columnconfigure(1, weight=1)

    # ---- עוזרי בנייה ----
    def _card(self, parent):
        outer = tk.Frame(parent, bg=BG)
        outer.pack(fill="both", expand=True, padx=6, pady=6)
        c = tk.Frame(outer, bg=CARD, bd=0, highlightbackground=BORDER, highlightthickness=1)
        c.pack(fill="both", expand=True)
        inner = tk.Frame(c, bg=CARD)
        inner.pack(fill="both", expand=True, padx=18, pady=16)
        return inner

    def _field(self, parent, label, row):
        ttk.Label(parent, text=label, style="Card.TLabel").grid(row=row, column=3, sticky="e", pady=(8, 2))
        e = ttk.Entry(parent, width=32)
        e.grid(row=row, column=1, columnspan=2, sticky="ew", padx=(0, 6), pady=(8, 2))
        return e

    # ---- לוג ----
    def _log(self, tag, msg):
        self.log.insert("end", msg + "\n", tag)
        self.log.see("end")

    def _clear_log(self):
        self.log.delete("1.0", "end")

    def _drain_log(self):
        try:
            while True:
                tag, msg = self.q.get_nowait()
                self._log(tag, msg)
        except queue.Empty:
            pass
        self.root.after(80, self._drain_log)

    # ---- בוחרי קבצים ----
    def _pick_out(self):
        d = filedialog.askdirectory()
        if d:
            self.e_out.delete(0, "end")
            self.e_out.insert(0, d)

    def _pick_tool(self, entry):
        f = filedialog.askopenfilename(filetypes=[("Executable", "*.exe"), ("All", "*.*")])
        if f:
            entry.delete(0, "end")
            entry.insert(0, f)

    def _pick_flash(self, key):
        f = filedialog.askopenfilename(title=f"בחר קובץ {key}",
                                       filetypes=[("Samsung firmware", "*.tar.md5 *.tar"), ("All", "*.*")])
        if f:
            self.flash_files[key] = f
            self.flash_labels[key].configure(text=os.path.basename(f), foreground=OK)

    # ---- samloader ----
    def _samloader_argv(self):
        sl = self.cfg.get("samloader", "").strip()
        if sl:
            return [sl]
        return [sys.executable, "-m", "samloader"]

    def act_checkupdate(self):
        model, region = self._model_region()
        if not model:
            return
        argv = self._samloader_argv() + ["-m", model, "-r", region, "checkupdate"]
        self._log("info", f"בודק גרסה עדכנית ל-{model} / {region}…")
        self.runner.run(argv)

    def act_download(self):
        model, region = self._model_region()
        if not model:
            return
        out = self.e_out.get().strip() or self.cfg.get("out_dir")
        os.makedirs(out, exist_ok=True)
        self.cfg["out_dir"] = out
        save_config(self.cfg)
        # samloader: download מוריד ואז אפשר decrypt; רבות מהגרסאות מפענחות אוטומטית עם --decrypt
        argv = self._samloader_argv() + ["-m", model, "-r", region, "download", "-O", out, "--decrypt"]
        self._log("info", f"מוריד קושחה אל {out} (עלול לקחת זמן רב)…")
        self.runner.run(argv)

    def _model_region(self):
        model = code_only(self.e_model.get())
        region = code_only(self.e_region.get())
        if not model or not region:
            messagebox.showwarning(APP_NAME, "הזן דגם ואזור (CSC).")
            return None, None
        return model, region

    # ---- Heimdall ----
    def _heimdall(self):
        h = self.cfg.get("heimdall", "").strip()
        if not h:
            messagebox.showwarning(APP_NAME, "נתיב heimdall לא מוגדר. הגדר בטאב 'הגדרות'.")
            return None
        return h

    def act_detect(self):
        h = self._heimdall()
        if not h:
            return
        self._log("info", "מחפש מכשיר ב-Download Mode…")
        self.runner.run([h, "detect"])

    def act_print_pit(self):
        h = self._heimdall()
        if not h:
            return

        def done(code):
            pass

        self._log("info", "קורא טבלת מחיצות (PIT)…")
        # אוספים את הפלט גם למיפוי אוטומטי
        self._capture_pit(h)

    def _capture_pit(self, h):
        def worker():
            try:
                creation = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0
                out = subprocess.run([h, "print-pit"], capture_output=True, text=True,
                                     encoding="utf-8", errors="replace", creationflags=creation)
                text = (out.stdout or "") + "\n" + (out.stderr or "")
                for ln in text.splitlines():
                    self.q.put(("out", ln))
                self.pit_map = parse_pit(text)
                if self.pit_map:
                    self.q.put(("ok", f"✓ נקראו {len(self.pit_map)} מחיצות מה-PIT."))
                else:
                    self.q.put(("warn", "לא זוהו מחיצות — ודא שהמכשיר ב-Download Mode ושהדרייבר מותקן."))
            except FileNotFoundError:
                self.q.put(("err", "✗ heimdall לא נמצא."))
            except Exception as e:
                self.q.put(("err", f"✗ {e}"))
        threading.Thread(target=worker, daemon=True).start()

    def act_prepare_flash(self):
        """מחלץ את קובצי ה-tar, ומפה כל image למחיצה לפי ה-PIT."""
        if not self.pit_map:
            messagebox.showinfo(APP_NAME, "קרא קודם PIT (כפתור 'קרא PIT') כדי לאפשר מיפוי אוטומטי.")
            return
        chosen = {k: v for k, v in self.flash_files.items() if v}
        if not chosen:
            messagebox.showwarning(APP_NAME, "בחר לפחות קובץ פלאש אחד (AP בדרך כלל חובה).")
            return
        workdir = os.path.join(self.cfg.get("out_dir", APP_DIR), "_extracted")
        os.makedirs(workdir, exist_ok=True)
        self._log("info", f"מחלץ קבצים אל {workdir}…")
        threading.Thread(target=self._prepare_worker, args=(chosen, workdir), daemon=True).start()

    def _prepare_worker(self, chosen, workdir):
        images = []
        for key, path in chosen.items():
            try:
                with tarfile.open(path, "r:*") as tar:
                    for m in tar.getmembers():
                        if not m.isfile():
                            continue
                        tar.extract(m, workdir)
                        images.append(os.path.join(workdir, m.name))
                        self.q.put(("out", f"  חולץ: {m.name}"))
            except Exception as e:
                self.q.put(("err", f"✗ כשל בחילוץ {os.path.basename(path)}: {e}"))
        # פענוח lz4 אם צריך
        decompressed = []
        for img in images:
            if img.endswith(".lz4"):
                try:
                    import lz4.frame  # type: ignore
                    tgt = img[:-4]
                    with open(img, "rb") as fi, open(tgt, "wb") as fo:
                        fo.write(lz4.frame.decompress(fi.read()))
                    decompressed.append(tgt)
                    self.q.put(("out", f"  פורק lz4: {os.path.basename(tgt)}"))
                except Exception as e:
                    self.q.put(("warn", f"לא ניתן לפרק {os.path.basename(img)} ({e}) — התקן 'pip install lz4'."))
            else:
                decompressed.append(img)
        # מיפוי לפי PIT: התאמת basename ל-Flash Filename
        pit_by_name = {p["filename"]: p["partition"] for p in self.pit_map}
        plan = []
        for img in decompressed:
            base = os.path.basename(img)
            part = pit_by_name.get(base)
            if part:
                plan.append((part, img))
        self._flash_plan = plan
        if plan:
            self.q.put(("ok", f"✓ תוכנית פלאש מוכנה ({len(plan)} מחיצות):"))
            for part, img in plan:
                self.q.put(("info", f"    --{part}  ←  {os.path.basename(img)}"))
            self.q.put(("info", "לחץ 'בצע פלאש' כדי לכתוב (יופיע אישור)."))
        else:
            self.q.put(("warn", "לא נמצאה התאמה בין ה-images ל-PIT. בדוק שקראת PIT מהמכשיר הנכון."))

    def act_flash(self):
        h = self._heimdall()
        if not h:
            return
        plan = getattr(self, "_flash_plan", None)
        if not plan:
            messagebox.showinfo(APP_NAME, "הכן קודם תוכנית פלאש ('הכן תוכנית פלאש').")
            return
        summary = "\n".join(f"--{p}  ←  {os.path.basename(f)}" for p, f in plan)
        if not messagebox.askyesno(APP_NAME,
                                   "לבצע פלאש למכשיר?\n\n" + summary +
                                   "\n\n⚠ פלאש שגוי או קושחה לא תואמת לדגם עלולים להזיק. "
                                   "ודא שהמכשיר ב-Download Mode ושהקושחה מדויקת לדגם. להמשיך?"):
            return
        argv = [h, "flash"]
        for part, img in plan:
            argv += [f"--{part}", img]
        self._log("warn", "מתחיל פלאש — אל תנתק את הכבל!")
        self.runner.run(argv)

    # ---- ADB ----
    def _adb(self):
        a = self.cfg.get("adb", "").strip()
        if not a:
            messagebox.showwarning(APP_NAME, "נתיב adb לא מוגדר. הגדר בטאב 'הגדרות'.")
            return None
        return a

    def act_adb_info(self):
        a = self._adb()
        if not a:
            return
        self._log("info", "קורא מידע מכשיר…")
        self.runner.run([a, "shell", "getprop", "ro.product.model"])

    def act_adb_reboot(self, target):
        a = self._adb()
        if not a:
            return
        argv = [a, "reboot"] + ([target] if target else [])
        pretty = target or "רגיל"
        self._log("info", f"מאתחל את המכשיר ({pretty})…")
        self.runner.run(argv)

    # ---- הגדרות ----
    def act_save_cfg(self):
        for key, e in self.cfg_entries.items():
            self.cfg[key] = e.get().strip()
        save_config(self.cfg)
        self._log("ok", "✓ ההגדרות נשמרו.")

    def act_check_tools(self):
        for key in ("heimdall", "adb"):
            p = self.cfg.get(key, "").strip()
            state = "✓ נמצא" if (p and os.path.isfile(p)) else ("✓ ב-PATH" if shutil.which(p or key) else "✗ חסר")
            tag = "ok" if state.startswith("✓") else "err"
            self._log(tag, f"{key}: {state} ({p or 'PATH'})")
        # samloader
        try:
            r = subprocess.run(self._samloader_argv() + ["--help"], capture_output=True, text=True,
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0)
            self._log("ok" if r.returncode == 0 else "err",
                      "samloader: ✓ זמין" if r.returncode == 0 else "samloader: ✗ לא זמין — התקן 'pip install samloader'")
        except Exception:
            self._log("err", "samloader: ✗ לא זמין — התקן 'pip install samloader'")


def main():
    root = tk.Tk()
    SamLabApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()

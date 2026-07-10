@echo off
REM הרצה ישירה ללא בנייה (למפתחים / בדיקה מהירה)
REM דרישה: Python 3.10+ מותקן
setlocal
echo מתקין תלויות ליבה...
python -m pip install -r requirements.txt

echo מתקין samloader (הורדת קושחה) — פורק martinetd התומך ב-Python 3.11+...
python -m pip install "https://github.com/martinetd/samloader/archive/refs/heads/master.zip"
if errorlevel 1 (
  echo.
  echo [אזהרה] התקנת samloader נכשלה. שאר התוכנה תעבוד; רק טאב "הורדת קושחה" לא יהיה זמין.
  echo.
)

echo מפעיל את מבריק לאב...
python samlab.py
endlocal

@echo off
REM הרצה ישירה ללא בנייה (למפתחים / בדיקה מהירה)
REM דרישה: Python 3.10+ מותקן
setlocal
echo מתקין תלויות ליבה...
python -m pip install -r requirements.txt

echo מתקין samloader (הורדת קושחה) — עוקף מגבלת גרסת Python...
python -m pip install --ignore-requires-python samloader
if errorlevel 1 (
  echo.
  echo [אזהרה] התקנת samloader נכשלה. שאר התוכנה תעבוד; רק טאב "הורדת קושחה" לא יהיה זמין.
  echo.
)

echo מפעיל את מבריק לאב...
python samlab.py
endlocal

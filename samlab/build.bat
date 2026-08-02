@echo off
REM ============================================================
REM  מבריק לאב (SamLab) — בניית קובץ EXE יחיד ל-Windows
REM  דרישה מוקדמת: Python 3.10+ מותקן ומופיע ב-PATH
REM ============================================================
setlocal
echo [1/3] מעדכן pip ומתקין תלויות...
python -m pip install --upgrade pip
python -m pip install -r requirements.txt pyinstaller
if errorlevel 1 (
  echo.
  echo שגיאה בהתקנת תלויות הליבה. ודא ש-Python 3.10+ מותקן ומחובר לרשת.
  pause
  exit /b 1
)
REM samloader: פורק martinetd התומך ב-Python 3.11+ (אותו CLI). לא חוסם בנייה
python -m pip install "https://github.com/martinetd/samloader/archive/refs/heads/master.zip"

echo [2/3] בונה את SamLab.exe...
pyinstaller --noconfirm --onefile --windowed --name SamLab samlab.py
if errorlevel 1 (
  echo שגיאה בבנייה.
  pause
  exit /b 1
)

echo [3/3] הושלם. הקובץ נמצא ב: dist\SamLab.exe
echo זכור להעתיק לצד ה-EXE את תיקיית tools\ (heimdall.exe, adb.exe).
pause
endlocal

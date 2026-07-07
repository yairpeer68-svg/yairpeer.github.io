@echo off
REM הרצה ישירה ללא בנייה (למפתחים / בדיקה מהירה)
REM דרישה: Python 3.10+ מותקן
setlocal
python -m pip install -r requirements.txt
python samlab.py
endlocal

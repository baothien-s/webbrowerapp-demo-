@echo off
cd /d "%~dp0"
javac WebSourceClient.java
if errorlevel 1 (
  echo COMPILE LOI!
  pause
  exit /b 1
)
java WebSourceClient
pause

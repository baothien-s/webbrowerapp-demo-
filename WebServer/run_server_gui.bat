@echo off
cd /d "%~dp0"
javac WebCacheServer.java ServerManagerGUI.java
if errorlevel 1 (
  echo COMPILE LOI!
  pause
  exit /b 1
)
java ServerManagerGUI
pause

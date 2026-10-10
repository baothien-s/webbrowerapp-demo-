@echo off
cd /d "%~dp0"
javac WebCacheServer.java
if errorlevel 1 (
  echo COMPILE LOI!
  pause
  exit /b 1
)
echo === WebCacheServer (port 8080) ===
echo Dashboard: http://localhost:8080/
java WebCacheServer
pause

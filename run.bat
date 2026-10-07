@echo off
cd /d "%~dp0"
call build.bat >nul || (echo Build failed - is the editor still open? & pause & exit /b 1)
start "" javaw -jar ruse-map-editor.jar %*

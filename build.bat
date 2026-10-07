@echo off
rem Builds the RUSE Map Editor (requires JDK 21 or newer)
setlocal
cd /d "%~dp0"
if exist build rmdir /s /q build
mkdir build\classes
dir /s /b src\*.java > build\sources.txt
javac --release 21 -encoding UTF-8 -d build\classes @build\sources.txt || exit /b 1
rem Language files (src\ruse\editor\i18n\messages_*.properties)
xcopy /s /y /q src\*.properties build\classes\ >nul || exit /b 1
jar --create --file ruse-map-editor.jar --main-class ruse.editor.Main -C build\classes . || exit /b 1
echo.
echo Done: ruse-map-editor.jar  (start with run.bat)

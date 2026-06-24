@echo off
setlocal

set "GRADLE_VERSION=9.3.1"
set "CACHE_DIR=%USERPROFILE%\.gradle\wrapper-cache\gradle-%GRADLE_VERSION%"
set "ZIP_FILE=%CACHE_DIR%.zip"
set "INSTALL_DIR=%CACHE_DIR%\gradle-%GRADLE_VERSION%"

if not exist "%INSTALL_DIR%\bin\gradle.bat" (
  if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"
  if not exist "%ZIP_FILE%" powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP_FILE%'"
  powershell -NoProfile -Command "if (Test-Path '%INSTALL_DIR%') { Remove-Item -Recurse -Force '%INSTALL_DIR%' }; Expand-Archive -Path '%ZIP_FILE%' -DestinationPath '%CACHE_DIR%'"
)

call "%INSTALL_DIR%\bin\gradle.bat" %*
exit /b %errorlevel%
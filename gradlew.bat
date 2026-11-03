@echo off
setlocal
set GRADLE_VERSION=8.13
set GRADLE_ROOT=%USERPROFILE%\.gradle\wrapper\dists\gradle-%GRADLE_VERSION%-bin\mosaic-bootstrap
set GRADLE_BIN=%GRADLE_ROOT%\gradle-%GRADLE_VERSION%\bin\gradle.bat
set ZIP=%GRADLE_ROOT%\gradle-%GRADLE_VERSION%-bin.zip
if not exist "%GRADLE_BIN%" (
  if not exist "%GRADLE_ROOT%" mkdir "%GRADLE_ROOT%"
  if not exist "%ZIP%" powershell -NoProfile -Command "Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP%'"
  powershell -NoProfile -Command "Expand-Archive -Force '%ZIP%' '%GRADLE_ROOT%'"
)
call "%GRADLE_BIN%" %*
exit /b %ERRORLEVEL%

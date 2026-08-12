@echo off
setlocal

set "JAVA_HOME=C:\tools\java\jdk-17.0.13"
set "ANDROID_HOME=C:\tools\android\sdk"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"

call "%~dp0gradlew.bat" --no-daemon :app:assembleDebug %*
exit /b %errorlevel%

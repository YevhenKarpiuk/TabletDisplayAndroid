@echo off
setlocal

set "JAVA_HOME=C:\tools\java\jdk-17.0.13"
set "ANDROID_HOME=C:\tools\android\sdk"
set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"
set "GRADLE_CMD=C:\tools\gradle\gradle-9.2.1\bin\gradle.bat"
if not exist "%GRADLE_CMD%" set "GRADLE_CMD=%~dp0gradlew.bat"

call "%GRADLE_CMD%" --no-daemon :app:assembleDebug %*
exit /b %errorlevel%

@echo off
setlocal EnableExtensions DisableDelayedExpansion
rem Locate the executable JAR without changing the caller's working directory.
set "CODEY_LAUNCH_JAR=%~dp0..\target\codey-0.1.0-SNAPSHOT.jar"
if not exist "%CODEY_LAUNCH_JAR%" (
    echo CodeY JAR is missing. Build it first: 1>&2
    echo   cd /d "%~dp0.." 1>&2
    echo   mvn package 1>&2
    exit /b 1
)
rem Use the repository .env by default; an explicit config directory takes priority.
if not defined CODEY_CONFIG_DIR set "CODEY_CONFIG_DIR=%~dp0.."
java -jar "%CODEY_LAUNCH_JAR%" %*
exit /b %errorlevel%

@echo off
REM run.bat — launch structurizr-renderer from Windows Command Prompt
REM Usage:  run.bat path\to\file.dsl [options]
REM Options are forwarded directly to the JAR (--help for full list).

SET "JAR=%~dp0target\structurizr-renderer-1.0.0-SNAPSHOT.jar"

IF NOT EXIST "%JAR%" (
    echo ERROR: JAR not found at %JAR%
    echo Build first: mvnw.cmd package -DskipTests
    EXIT /B 1
)

java -jar "%JAR%" %*
EXIT /B %ERRORLEVEL%

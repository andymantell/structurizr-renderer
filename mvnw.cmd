@REM Maven Wrapper launcher for Windows
@REM Licensed under Apache License 2.0

@echo off
setlocal

@REM Project base dir (folder containing this script)
SET "MAVEN_PROJECTBASEDIR=%~dp0"
IF "%MAVEN_PROJECTBASEDIR:~-1%"=="\" SET "MAVEN_PROJECTBASEDIR=%MAVEN_PROJECTBASEDIR:~0,-1%"

SET "WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%\.mvn\wrapper\maven-wrapper.jar"
SET "WRAPPER_URL=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"

@REM Download maven-wrapper.jar if missing
IF NOT EXIST "%WRAPPER_JAR%" (
  echo Downloading maven-wrapper.jar ...
  powershell -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile('%WRAPPER_URL%', '%WRAPPER_JAR%')"
  IF NOT EXIST "%WRAPPER_JAR%" (
    echo ERROR: Failed to download maven-wrapper.jar 1>&2
    exit /B 1
  )
)

@REM Locate java
IF DEFINED JAVA_HOME (
  SET "JAVACMD=%JAVA_HOME%\bin\java.exe"
) ELSE (
  SET "JAVACMD=java"
)

"%JAVACMD%" %MAVEN_OPTS% ^
  -classpath "%WRAPPER_JAR%" ^
  "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" ^
  org.apache.maven.wrapper.MavenWrapperMain ^
  %*

EXIT /B %ERRORLEVEL%

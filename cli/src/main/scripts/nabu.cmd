@echo off
rem nabu: starts the version of Nabu that the file `current` names. `nabu self-update` installs a new version next to
rem the old one and changes `current`, so this launcher does not change when Nabu is updated.
rem NABU_JAVA_HOME or JAVA_HOME name the JDK (21 or later); NABU_JAVA_OPTS are options for the JVM, for example -Xmx3g.
setlocal
for %%i in ("%~dp0..") do set "NABU_HOME_DIR=%%~fi"
set "NABU_VERSION="
if exist "%NABU_HOME_DIR%\current" set /p NABU_VERSION=<"%NABU_HOME_DIR%\current"
if not defined NABU_VERSION (
    echo nabu: %NABU_HOME_DIR%\current is missing or empty; install nabu again 1>&2
    exit /b 1
)
if not exist "%NABU_HOME_DIR%\versions\%NABU_VERSION%\lib" (
    echo nabu: version %NABU_VERSION% is not in %NABU_HOME_DIR%\versions; install nabu again 1>&2
    exit /b 1
)
set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java"
if defined NABU_JAVA_HOME set "JAVA_EXE=%NABU_JAVA_HOME%\bin\java"
"%JAVA_EXE%" %NABU_JAVA_OPTS% --enable-native-access=ALL-UNNAMED "-Dnabu.home=%NABU_HOME_DIR%" "-Dnabu.version=%NABU_VERSION%" -cp "%NABU_HOME_DIR%\versions\%NABU_VERSION%\lib\*" io.github.potjerodekool.nabu.cli.Nabu %*
exit /b %ERRORLEVEL%

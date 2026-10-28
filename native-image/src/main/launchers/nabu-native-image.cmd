@echo off
rem nabu-native-image: compiles a Java program (jars, a class directory) to a native executable.
rem This is the launcher of an installation of Nabu (%USERPROFILE%\.nabu\bin): it starts the version that the file
rem `current` names, so it does not change when `nabu self-update` installs a new version.
rem NABU_JAVA_HOME or JAVA_HOME name the JDK (21 or later); NABU_JAVA_OPTS are options for the JVM, for example -Xmx3g.
setlocal
for %%i in ("%~dp0..") do set "NABU_HOME_DIR=%%~fi"
set "NABU_VERSION="
if exist "%NABU_HOME_DIR%\current" set /p NABU_VERSION=<"%NABU_HOME_DIR%\current"
if not defined NABU_VERSION (
    echo nabu-native-image: %NABU_HOME_DIR%\current is missing or empty; install nabu again 1>&2
    exit /b 1
)
set "LIB_DIR=%NABU_HOME_DIR%\versions\%NABU_VERSION%\lib"
if not exist "%LIB_DIR%\nabu-native-image-*.jar" (
    echo nabu-native-image: the native backend is not installed in version %NABU_VERSION% ^(nabu self-update --native^) 1>&2
    exit /b 1
)
set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java"
if defined NABU_JAVA_HOME set "JAVA_EXE=%NABU_JAVA_HOME%\bin\java"
"%JAVA_EXE%" %NABU_JAVA_OPTS% --enable-native-access=ALL-UNNAMED -cp "%LIB_DIR%\*" io.github.potjerodekool.nabu.nativeimage.NativeImage %*
exit /b %ERRORLEVEL%

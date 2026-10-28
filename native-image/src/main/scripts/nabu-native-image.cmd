@echo off
rem nabu-native-image: compiles a Java program (jars, a class directory) to a native executable.
rem Set NABU_JAVA_OPTS for options of the JVM that runs the compiler, for example NABU_JAVA_OPTS=-Xmx3g.
setlocal
set "HOME_DIR=%~dp0.."
set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java"
if defined NABU_JAVA_HOME set "JAVA_EXE=%NABU_JAVA_HOME%\bin\java"
"%JAVA_EXE%" %NABU_JAVA_OPTS% --enable-native-access=ALL-UNNAMED -cp "%HOME_DIR%\lib\*" io.github.potjerodekool.nabu.nativeimage.NativeImage %*
exit /b %ERRORLEVEL%

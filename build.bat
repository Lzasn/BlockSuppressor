@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem ============================================================
rem  BlockSuppressor build script
rem  Usage:
rem    build.bat                       (uses ..\test\26.2)
rem    build.bat "D:\servers\folia"    (custom server dir)
rem    build.bat "D:\servers\folia" install
rem ============================================================

set "SERVER_DIR=%~1"
if "%SERVER_DIR%"=="" set "SERVER_DIR=%~dp0..\test\26.2"

if not exist "%SERVER_DIR%\versions" (
    echo [ERROR] Folia server directory not found: %SERVER_DIR%
    echo         Usage: build.bat "C:\path\to\server" [install]
    exit /b 1
)

rem ---- locate JDK 25 (the server itself runs Java 25) ----
set "JAVAC="
set "JARTOOL="
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\javac.exe" (
        set "JAVAC=%JAVA_HOME%\bin\javac.exe"
        set "JARTOOL=%JAVA_HOME%\bin\jar.exe"
    )
)
if not defined JAVAC (
    for /d %%D in ("C:\Program Files\Microsoft\jdk-25*") do (
        if exist "%%D\bin\javac.exe" (
            set "JAVAC=%%D\bin\javac.exe"
            set "JARTOOL=%%D\bin\jar.exe"
        )
    )
)
if not defined JAVAC (
    for /d %%D in ("C:\Program Files\Java\jdk-25*") do (
        if exist "%%D\bin\javac.exe" (
            set "JAVAC=%%D\bin\javac.exe"
            set "JARTOOL=%%D\bin\jar.exe"
        )
    )
)
if not defined JAVAC (
    echo [ERROR] JDK 25 not found. Install it or set JAVA_HOME.
    exit /b 1
)

rem ---- classpath: server API + the few API deps javac needs ----
set "CP="
call :findjar "%SERVER_DIR%\libraries\io\nanachiyo0721\shiroha-api"
if errorlevel 1 exit /b 1
call :findjar "%SERVER_DIR%\libraries\net\kyori\adventure-api"
if errorlevel 1 exit /b 1
call :findjar "%SERVER_DIR%\libraries\net\kyori\adventure-key"
if errorlevel 1 exit /b 1
call :findjar "%SERVER_DIR%\libraries\net\kyori\adventure-text-serializer-plain"
if errorlevel 1 exit /b 1
call :findjar "%SERVER_DIR%\libraries\com\google\guava\guava"
if errorlevel 1 exit /b 1
call :findjar "%SERVER_DIR%\libraries\net\md-5\bungeecord-chat"
if errorlevel 1 exit /b 1

if not exist "libs\annotations-26.0.2.jar" (
    echo [ERROR] missing libs\annotations-26.0.2.jar
    exit /b 1
)
set "CP=!CP!;%~dp0libs\annotations-26.0.2.jar"

echo [1/4] javac: %JAVAC%
echo [2/4] server: %SERVER_DIR%

if exist build rmdir /s /q build
mkdir build\classes
dir /s /b src\main\java\*.java > build\sources.txt

echo [3/4] compiling...
"%JAVAC%" -encoding UTF-8 -nowarn -d build\classes -cp "!CP!" @build\sources.txt
if errorlevel 1 (
    echo [ERROR] compile failed
    exit /b 1
)

> build\MANIFEST.MF echo Manifest-Version: 1.0
>> build\MANIFEST.MF echo Implementation-Title: BlockSuppressor
>> build\MANIFEST.MF echo Implementation-Version: 1.3.0
>> build\MANIFEST.MF echo Implementation-Vendor: Lzasn

"%JARTOOL%" --create --file build\BlockSuppressor-1.3.0.jar --manifest build\MANIFEST.MF -C build\classes . -C src\main\resources .
if errorlevel 1 (
    echo [ERROR] packaging failed
    exit /b 1
)

echo [4/4] done: %~dp0build\BlockSuppressor-1.3.0.jar

if /i "%~2"=="install" (
    if not exist "%SERVER_DIR%\plugins" mkdir "%SERVER_DIR%\plugins"
    copy /y build\BlockSuppressor-1.3.0.jar "%SERVER_DIR%\plugins\" >nul
    echo      installed to %SERVER_DIR%\plugins
)

endlocal
exit /b 0

rem ---- helper: append the newest jar found under a directory ----
:findjar
set "FOUND_JAR="
for /r "%~1" %%J in (*.jar) do set "FOUND_JAR=%%J"
if not defined FOUND_JAR (
    echo [ERROR] cannot find any jar under: %~1
    exit /b 1
)
set "CP=!CP!;!FOUND_JAR!"
exit /b 0

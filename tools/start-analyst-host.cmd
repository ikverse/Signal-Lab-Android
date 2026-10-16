@echo off
rem Starts Signal Lab's analyst host on this PC: Claude Code, signed in as it is, answering the phone's analyst.
rem Run it by double-clicking, or from a terminal. Keep the window open while you use the analyst; close it to stop.
rem Options are passed on, for example:  start-analyst-host.cmd --new-code   (every paired phone must pair again)

setlocal
cd /d "%~dp0.."
if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not defined JAVA_HOME (
  echo Java was not found. Install Android Studio, or set JAVA_HOME to a Java 11 or newer.
  pause
  exit /b 1
)
echo Building the analyst host...
call "%~dp0..\gradlew.bat" :analyst-host:installDist -q
if errorlevel 1 (
  echo The build failed.
  pause
  exit /b 1
)
call "%~dp0..\analyst-host\build\install\signal-lab-host\bin\signal-lab-host.bat" %*
pause

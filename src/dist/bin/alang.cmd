@echo off
rem ALang launcher in the release archive for Windows (cmd.exe and PowerShell): runs lib\alang.jar
rem with Java 21 or newer (JAVA_HOME, else java on PATH). bin\alang is the same launcher for sh.
rem
rem Java reads command-line arguments in the Windows code page, and cmd.exe and PowerShell can drop
rem the double quotes inside a JSON argument on its way to a batch file. So give `alang call` its
rem arguments on stdin, as UTF-8: run `alang call prog.al tool -` with a JSON file redirected into it
rem in cmd.exe, or piped into it from Get-Content in PowerShell. (No redirection or pipe characters
rem in these comments: cmd.exe might act on them.)
setlocal
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"
"%JAVA%" -jar "%~dp0..\lib\alang.jar" %*
exit /b %ERRORLEVEL%

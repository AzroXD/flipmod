@echo off
REM Builds flipmod on Windows. Needs JDK 25 and Gradle installed and on PATH.
java -version 2>&1 | findstr /C:"25" >nul || (echo Java 25 not found. Install "Temurin JDK 25" first. & pause & exit /b 1)
where gradle >nul 2>&1 || (echo Gradle not found. Install it from https://gradle.org/install/ & pause & exit /b 1)
gradle build
if errorlevel 1 (echo. & echo BUILD FAILED - copy the red error text and send it to Claude. & pause & exit /b 1)
echo.
echo Done. Your mod is in build\libs\flipmod-0.1.0.jar
pause

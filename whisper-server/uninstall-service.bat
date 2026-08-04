@echo off
REM ============================================================================
REM Jeeves Whisper Server - Windows Service Uninstaller
REM ============================================================================
REM
REM Removes the whisper-server Windows Service.
REM Run as Administrator.
REM ============================================================================

setlocal

REM Check for admin rights
net session >nul 2>&1
if errorlevel 1 (
    echo ERROR: This script must be run as Administrator.
    echo Right-click and select "Run as administrator"
    pause
    exit /b 1
)

set SERVICE_NAME=JeevesWhisper

echo ============================================
echo Jeeves Whisper Server - Service Uninstaller
echo ============================================
echo.

REM Check if service exists
sc query %SERVICE_NAME% >nul 2>&1
if errorlevel 1 (
    echo Service %SERVICE_NAME% is not installed.
    pause
    exit /b 0
)

echo Stopping service...
nssm stop %SERVICE_NAME% >nul 2>&1
timeout /t 2 >nul

echo Removing service...
nssm remove %SERVICE_NAME% confirm

echo.
echo ============================================
echo Service %SERVICE_NAME% has been uninstalled.
echo ============================================
echo.
echo Note: Log files remain at %USERPROFILE%\Jeeves\logs\
echo.
pause

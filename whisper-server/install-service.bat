@echo off
REM ============================================================================
REM Jeeves Whisper Server - Windows Service Installer
REM ============================================================================
REM 
REM Installs whisper-server as a Windows Service using NSSM (Non-Sucking Service Manager).
REM The service will auto-start on boot and restart on failure.
REM
REM Prerequisites:
REM   1. NSSM installed and in PATH (download from https://nssm.cc/download)
REM   2. Python 3.10+ with venv created in whisper-server directory
REM   3. Run this script as Administrator
REM
REM Usage:
REM   install-service.bat           - Install with default settings
REM   install-service.bat small     - Install with specific model (tiny/base/small/medium/large-v3)
REM
REM Environment variables (optional):
REM   WHISPER_PORT       - Server port (default: 8178)
REM   WHISPER_BACKEND    - Force backend: mlx, cuda, cpu (default: auto-detect)
REM   WHISPER_IDLE_UNLOAD - Minutes before unloading model (default: 0 = never)
REM ============================================================================

setlocal enabledelayedexpansion

REM Check for admin rights
net session >nul 2>&1
if errorlevel 1 (
    echo ERROR: This script must be run as Administrator.
    echo Right-click and select "Run as administrator"
    pause
    exit /b 1
)

REM Check NSSM is installed
where nssm >nul 2>&1
if errorlevel 1 (
    echo ERROR: NSSM not found in PATH.
    echo.
    echo Please install NSSM:
    echo   1. Download from https://nssm.cc/download
    echo   2. Extract nssm.exe to a folder in your PATH (e.g., C:\Windows\System32)
    echo   3. Or run: winget install nssm
    echo.
    pause
    exit /b 1
)

REM Configuration
set SERVICE_NAME=JeevesWhisper
set SCRIPT_DIR=%~dp0
set PYTHON_PATH=%SCRIPT_DIR%.venv\Scripts\python.exe
set SERVER_SCRIPT=%SCRIPT_DIR%server.py
set LOG_DIR=%USERPROFILE%\Jeeves\logs

REM Model size from argument or default
set WHISPER_MODEL=%1
if "%WHISPER_MODEL%"=="" set WHISPER_MODEL=small

REM Port from env or default
if "%WHISPER_PORT%"=="" set WHISPER_PORT=8178

echo ============================================
echo Jeeves Whisper Server - Service Installer
echo ============================================
echo.
echo Service Name: %SERVICE_NAME%
echo Model: %WHISPER_MODEL%
echo Port: %WHISPER_PORT%
echo.

REM Check Python venv exists
if not exist "%PYTHON_PATH%" (
    echo ERROR: Python venv not found at %PYTHON_PATH%
    echo.
    echo Please create a virtual environment first:
    echo   cd %SCRIPT_DIR%
    echo   python -m venv .venv
    echo   .venv\Scripts\pip install -r requirements.txt
    echo.
    pause
    exit /b 1
)

REM Check if service already exists
sc query %SERVICE_NAME% >nul 2>&1
if not errorlevel 1 (
    echo Service %SERVICE_NAME% already exists.
    echo.
    set /p REINSTALL="Do you want to reinstall? (y/n): "
    if /i "!REINSTALL!"=="y" (
        echo Stopping and removing existing service...
        nssm stop %SERVICE_NAME% >nul 2>&1
        nssm remove %SERVICE_NAME% confirm
        timeout /t 2 >nul
    ) else (
        echo Installation cancelled.
        exit /b 0
    )
)

REM Create log directory
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

echo.
echo Installing service...

REM Install service
nssm install %SERVICE_NAME% "%PYTHON_PATH%"
nssm set %SERVICE_NAME% AppParameters "%SERVER_SCRIPT%"
nssm set %SERVICE_NAME% AppDirectory "%SCRIPT_DIR%"

REM Set environment variables
nssm set %SERVICE_NAME% AppEnvironmentExtra WHISPER_MODEL=%WHISPER_MODEL% WHISPER_PORT=%WHISPER_PORT%

REM Configure logging
nssm set %SERVICE_NAME% AppStdout "%LOG_DIR%\whisper-server.log"
nssm set %SERVICE_NAME% AppStderr "%LOG_DIR%\whisper-server-error.log"
nssm set %SERVICE_NAME% AppStdoutCreationDisposition 4
nssm set %SERVICE_NAME% AppStderrCreationDisposition 4
nssm set %SERVICE_NAME% AppRotateFiles 1
nssm set %SERVICE_NAME% AppRotateBytes 10485760

REM Configure service behavior
nssm set %SERVICE_NAME% DisplayName "Jeeves Whisper Transcription Server"
nssm set %SERVICE_NAME% Description "Local Whisper speech-to-text server for Jeeves meeting recorder"
nssm set %SERVICE_NAME% Start SERVICE_AUTO_START
nssm set %SERVICE_NAME% AppThrottle 5000
nssm set %SERVICE_NAME% AppRestartDelay 3000

REM Start the service
echo.
echo Starting service...
nssm start %SERVICE_NAME%

REM Wait and check status
timeout /t 3 >nul
sc query %SERVICE_NAME% | findstr "RUNNING" >nul
if errorlevel 1 (
    echo.
    echo WARNING: Service may not have started correctly.
    echo Check logs at: %LOG_DIR%\whisper-server.log
) else (
    echo.
    echo ============================================
    echo Service installed and started successfully!
    echo ============================================
    echo.
    echo Service: %SERVICE_NAME%
    echo Status: RUNNING
    echo Port: http://localhost:%WHISPER_PORT%
    echo Logs: %LOG_DIR%\whisper-server.log
    echo.
    echo Commands:
    echo   nssm stop %SERVICE_NAME%     - Stop service
    echo   nssm start %SERVICE_NAME%    - Start service
    echo   nssm restart %SERVICE_NAME%  - Restart service
    echo   nssm remove %SERVICE_NAME%   - Uninstall service
    echo.
    echo Test endpoint:
    echo   curl http://localhost:%WHISPER_PORT%/health
)

echo.
pause

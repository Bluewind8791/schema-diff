@echo off
rem 더블클릭으로 실행. 서버를 띄우고 브라우저를 연다. 이 창을 닫으면 종료된다.
chcp 65001 >nul
title SchemaDiff
cd /d "%~dp0"
where java >nul 2>nul || (echo Java 11 이상이 필요합니다. & pause & exit /b 1)
if not exist schemadiff.jar (echo schemadiff.jar 가 없습니다. build.bat 을 먼저 실행하세요. & pause & exit /b 1)
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -jar schemadiff.jar
if errorlevel 1 pause

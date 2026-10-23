@echo off
rem 개발 실행. 소스를 build\dev 로 컴파일해 포트 8766, 데이터 dev-data\ 로 띄운다. 평소 쓰는 SchemaDiff(8765)와 함께 켤 수 있다.
rem 화면 파일은 src\web 에서 바로 읽으므로 app.js·style.css 는 고친 뒤 브라우저만 새로 고치면 된다.
rem Java 를 고쳤으면 이 창을 닫고 다시 실행한다(창을 닫지 않으면 포트가 겹쳐 새로 띄우지 못한다).
chcp 65001 >nul
title SchemaDiff (dev)
setlocal
cd /d "%~dp0"
if exist build\dev rmdir /s /q build\dev
mkdir build\dev
javac --release 11 -encoding UTF-8 -cp "lib\*" -d build\dev src\schemadiff\*.java || (pause & exit /b 1)
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dschemadiff.port=8766 -Dschemadiff.data=dev-data -Dschemadiff.web=src\web -cp "build\dev;lib\*" schemadiff.Main %*
if errorlevel 1 pause

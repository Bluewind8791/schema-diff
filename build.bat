@echo off
rem schemadiff.jar 를 만든다. JDK 11 이상의 javac·jar 만 쓴다. lib\*.jar 를 풀어 jar 하나에 넣는다.
chcp 65001 >nul
setlocal
cd /d "%~dp0"
if exist build rmdir /s /q build
mkdir build\classes
javac --release 11 -encoding UTF-8 -cp "lib\*" -d build\classes src\schemadiff\*.java || goto :fail
xcopy /e /i /q src\web build\classes\web >nul || goto :fail
pushd build\classes
for %%j in (..\..\lib\*.jar) do jar xf "%%j"
del /q META-INF\*.SF META-INF\*.RSA META-INF\*.DSA META-INF\*.EC META-INF\MANIFEST.MF module-info.class 2>nul
popd
jar --create --file schemadiff.jar --main-class schemadiff.Main -C build\classes . || goto :fail
echo 빌드 완료: schemadiff.jar
exit /b 0
:fail
echo 빌드 실패
exit /b 1

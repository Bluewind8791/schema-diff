@echo off
rem dist\SchemaDiff.exe 를 만든다. JDK 11 이상의 javac·jar 와 Launch4j 를 쓴다. lib\*.jar 를 풀어 jar 하나에 넣고 exe 로 감싼다.
rem 실행 중인 SchemaDiff 와 상관없이 빌드된다(평소에는 dist 의 exe 를 다른 폴더에 복사해 쓴다). dev.bat 이 쓰는 build\dev 는 지우지 않는다.
chcp 65001 >nul
setlocal
cd /d "%~dp0"
if not defined LAUNCH4J_HOME set "LAUNCH4J_HOME=%ProgramFiles(x86)%\Launch4j"
if not exist "%LAUNCH4J_HOME%\launch4j.jar" (echo Launch4j 가 없습니다. choco install launch4j 로 설치하거나 LAUNCH4J_HOME 을 설정하세요. & goto :fail)
if exist build\classes rmdir /s /q build\classes
mkdir build\classes
javac --release 11 -encoding UTF-8 -cp "lib\*" -d build\classes src\schemadiff\*.java || goto :fail
rem 진입점 Boot 는 Java 8 에서도 읽혀야 「Java 11 이상 필요」 안내를 띄울 수 있다
javac --release 8 -encoding UTF-8 -d build\classes src\boot\schemadiff\Boot.java || goto :fail
xcopy /e /i /q src\web build\classes\web >nul || goto :fail
pushd build\classes
for %%j in (..\..\lib\*.jar) do jar xf "%%j"
del /q META-INF\*.SF META-INF\*.RSA META-INF\*.DSA META-INF\*.EC META-INF\MANIFEST.MF module-info.class 2>nul
popd
jar --create --file build\schemadiff.jar --main-class schemadiff.Boot -C build\classes . || goto :fail
if not exist dist mkdir dist
rem launch4jc.exe 는 그 자신도 Launch4j 로 감싼 exe 라 레지스트리에 Java 가 없는 PC 에서는 뜨지 않는다. jar 를 직접 실행한다
java -jar "%LAUNCH4J_HOME%\launch4j.jar" "%~dp0launch4j.xml" || goto :fail
echo 빌드 완료: dist\SchemaDiff.exe
exit /b 0
:fail
echo 빌드 실패
exit /b 1

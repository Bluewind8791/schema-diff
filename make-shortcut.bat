@echo off
rem SchemaDiff.bat 을 여는 아이콘 바로가기(SchemaDiff.lnk)를 이 폴더에 만든다. 바탕화면·작업 표시줄로 옮겨 쓴다.
chcp 65001 >nul
cd /d "%~dp0"
powershell -NoProfile -Command "$s = (New-Object -ComObject WScript.Shell).CreateShortcut('%~dp0SchemaDiff.lnk'); $s.TargetPath = '%~dp0SchemaDiff.bat'; $s.WorkingDirectory = '%~dp0'; $s.IconLocation = '%~dp0schemadiff.ico,0'; $s.Save()" || (pause & exit /b 1)
echo SchemaDiff.lnk 를 만들었습니다.
pause
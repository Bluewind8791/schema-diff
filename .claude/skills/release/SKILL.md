---
name: release
description: SchemaDiff 새 버전을 빌드해 GitHub Releases 에 SchemaDiff.exe 로 배포한다. 「배포해줘」, 「릴리스 만들어줘」, 「새 버전 올려줘」, /release 일 때 사용.
---

# SchemaDiff 배포

`build.bat` 으로 `dist\SchemaDiff.exe` 를 만들고, 버전 태그의 GitHub Release 에 첨부한다. exe 는 git 에 커밋하지 않는다(`.gitignore`).
저장소 `Bluewind8791/schema-diff` 는 **공개**다. push 와 릴리스 생성은 누구나 보게 되므로 단계 5 에서 반드시 사용자 확인을 받는다.

## 1. 상태 확인

- `git status --short` 가 깨끗해야 한다. 커밋 안 된 변경이 있으면 커밋할지 사용자에게 묻는다.
  - 커밋하기 전에 `git diff --cached --stat` 으로 이미 스테이징된 것(예: 앞서 `git rm` 한 파일)이 있는지 본다. 그대로 두면 엉뚱한 커밋에 섞인다.
- `git fetch` 후 `main` 이 `origin/main` 보다 뒤처져 있지 않은지 본다.
- 지난 릴리스: `gh release list --limit 5`. 그 태그 이후 커밋: `git log --oneline <지난 태그>..HEAD`.
  - 지난 태그 이후 커밋이 없으면 배포할 것이 없다고 알리고 멈춘다.

## 2. 버전 정하기

태그는 `vMAJOR.MINOR.PATCH`. 코드 안에 버전 문자열은 없고 태그가 곧 버전이다.
커밋 목록을 보고 제안한 뒤 사용자에게 정하게 한다.

- 버그 수정만: PATCH
- 기능 추가·화면 변경: MINOR
- 데이터 폴더(`data/`) 형식이 바뀌는 등 기존 사용자가 손대야 하는 변경: MAJOR

## 3. 빌드

```powershell
cmd /c "D:\other_proj\schema_diff\build.bat"
```

- 끝에 `빌드 완료: dist\SchemaDiff.exe` 가 나와야 한다. `dist\SchemaDiff.exe` 크기가 대략 10MB 인지 본다.
- Launch4j 는 `C:\Program Files (x86)\Launch4j`(`choco install launch4j`) 또는 `LAUNCH4J_HOME` 에서 찾는다.
- `build.bat` 은 `launch4jc.exe` 대신 `java -jar launch4j.jar` 를 쓴다. `launch4jc.exe` 는 그 자신도 Launch4j exe 라서, 레지스트리(JavaSoft)에 Java 가 없는 PC(이 PC 가 그렇다)에서 「requires a Java Runtime Environment」 로 실패한다. 되돌리지 않는다.
- 쓰는 중인 루트 `SchemaDiff.exe` 가 실행 중이어도 빌드는 `dist\` 에만 쓰므로 막히지 않는다.

## 4. 실행 확인

exe 를 임시 폴더(스크래치패드)에 복사해 다른 포트로 띄우고 ping 한다. 평소 쓰는 SchemaDiff(8765)와 겹치지 않게 포트를 바꾼다. 8765 근처 포트는 Windows 가 예약해 두어 bind 가 실패할 수 있으니 18433 처럼 먼 포트를 쓴다.

```powershell
$d = "<스크래치패드>\release-test"; New-Item -ItemType Directory -Force $d | Out-Null
Copy-Item D:\other_proj\schema_diff\dist\SchemaDiff.exe $d -Force
$env:JAVA_TOOL_OPTIONS = "-Dschemadiff.port=18433"
Start-Process "$d\SchemaDiff.exe" -ArgumentList "--no-browser" -WorkingDirectory $d
Start-Sleep 6
$env:JAVA_TOOL_OPTIONS = $null
(Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18433/api/ping -Headers @{ 'X-SchemaDiff' = '1' }).Content   # {"app":"schemadiff"}
(Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18433/).StatusCode                                           # 200
Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -like "*release-test*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Remove-Item -Recurse -Force $d
```

- 실패하면 `SchemaDiff.exe --l4j-debug` 로 띄워 exe 옆 `launch4j.log` 에서 어떤 java 를 골랐는지 본다.
- 화면 전체를 캡처하지 않는다(사용자의 다른 창이 찍힌다). 창을 봐야 하면 그 프로세스의 창만 `PrintWindow` 로 찍는다.

## 5. 릴리스 (사용자 확인 후)

릴리스 설명 초안을 보여 주고 확인을 받는다. 지난 릴리스 설명(`gh release view <지난 태그>`)의 형식을 따른다.

- 「실행」 절은 그대로 둔다: exe 내려받기 → 더블클릭 → 상태 창을 닫으면 종료, Java 11 이상 필요, `data\` 위치, SmartScreen 경고 대처.
- 「이번 버전」 절에는 지난 태그 이후 사용자가 체감하는 변경만 적는다. 내부 리팩터링·빌드 변경은 뺀다.

확인을 받으면:

```bash
git push origin main
gh release create vX.Y.Z dist/SchemaDiff.exe --target main --title "SchemaDiff vX.Y.Z" --notes-file - <<'EOF'
(확인받은 설명)
EOF
gh release view vX.Y.Z --json url,assets --jq '.url, (.assets[] | "\(.name) \(.size)")'
```

커밋 메시지는 사용자의 전역 규칙을 따른다(AI 기여 표기 트레일러를 넣지 않는다).

## 6. 이 PC 의 실행 파일 교체

사용자는 저장소 루트의 `SchemaDiff.exe` 를 평소에 쓴다(`data\` 도 루트에 있다). 교체할지 묻는다.

- 실행 중이면 덮어쓸 수 없다. 「SchemaDiff 실행 중」 창을 닫아 달라고 하고, 닫았다는 답을 받은 뒤 `Copy-Item dist\SchemaDiff.exe SchemaDiff.exe -Force`.
- 사용자가 띄운 SchemaDiff 프로세스를 직접 끄지 않는다.

## 마무리 보고

릴리스 URL, 태그, exe 크기, 실행 확인 결과, 루트 exe 교체 여부를 알린다.

# <img src="src/web/favicon.svg" width="32" height="32" alt=""> SchemaDiff

로컬에서 실행하는 DB 스키마 비교 도구. FROM(기준)·TO(대상) DB 에 직접 접속해 메타데이터를 비교하고, TO 를 FROM 에 맞추는 동기화 SQL 을 만든다. 현재 Oracle 만 지원.

## 실행

- 필요한 것: JDK/JRE 11 이상 (`java` 가 PATH 에 있어야 함)
- `SchemaDiff.bat` 더블클릭 → 브라우저에 `http://127.0.0.1:8765/` 가 열린다. 콘솔 창을 닫으면 종료.
- 다른 PC 로 옮길 때는 `schemadiff.jar` 와 `SchemaDiff.bat` 두 파일만 복사하면 된다. 아이콘 바로가기를 쓰려면 `schemadiff.ico`·`make-shortcut.bat` 도 복사한다.
- 아이콘 바로가기: `make-shortcut.bat` 을 실행하면 같은 폴더에 `SchemaDiff.lnk` 가 생긴다. 바탕화면이나 작업 표시줄로 옮겨 쓴다(`.bat` 파일 자체에는 아이콘을 붙일 수 없다).

## 빌드

`build.bat` (JDK 11 이상의 `javac`·`jar` 사용). `lib/*.jar` 를 풀어 `schemadiff.jar` 하나로 묶는다.

- `lib/ojdbc11.jar`: Oracle JDBC 23.9. 비밀번호가 옛 10G 방식인 계정도 접속된다(python-oracledb thin 은 `DPY-3015` 로 실패).
- `lib/jackson-*.jar`: JSON.

## 데이터 (`data/`, jar 옆에 생김)

| 파일 | 내용 |
|---|---|
| `connections.json` | 저장된 연결. 비밀번호는 Windows DPAPI(CurrentUser)로 암호화 — 이 PC 의 이 Windows 계정에서만 풀린다 |
| `settings.json` | `exclude`: 비교에서 뺄 백업·임시 테이블·시퀀스 이름 정규식 |
| `history/` | 비교 결과(`*.json.gz`)와 목록용 요약(`*.meta.json`). 화면의 「이력」에서 다시 연다 |

## 비교 규칙

- 오브젝트: 테이블(컬럼·주석), 인덱스, 제약조건, 시퀀스, 뷰(컬럼·주석), 프로시저, 함수, 트리거, 패키지·타입
- NUMBER(*,0)=NUMBER(38), DEFAULT NULL=기본값 없음, 컬럼 순서는 비교하지 않는다(「컬럼 순서만 다름」으로 표시)
- 뷰·코드 원문은 공백·빈 줄 차이를 무시하고, 원문 안의 자기 스키마 접두어(`APP_OWNER.`)를 떼고 비교한다
- 시스템 생성 이름(SYS_C…)의 인덱스·제약조건은 구성으로 짝짓고, 이름만 다르면 RENAME 을 낸다
- 동기화 SQL: TO 에만 있는 것의 DROP 은 주석으로만 낸다. 길이 축소·타입 변경·NOT NULL 추가는 「주의」 주석. FROM 주석이 비었거나 깨진(`??`) 경우 TO 주석을 덮지 않는다

## 보안

`127.0.0.1` 에만 열린다. Host 헤더가 `127.0.0.1:8765`/`localhost:8765` 가 아니거나 API 요청에 `X-SchemaDiff: 1` 헤더가 없으면 거부한다(다른 사이트의 스크립트가 이 서버를 부르지 못하게).

## 새 DBMS 추가

`Adapter` 인터페이스를 구현하고 `Main.ADAPTERS` 에 등록, 화면 `app.js` 의 `DBMS` 목록에서 활성화한다. 드라이버 jar 는 `lib/` 에 넣는다.

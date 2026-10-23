package schemadiff;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * DBMS 별 어댑터. 메타데이터 조회와 DDL·동기화 SQL 생성을 맡는다.
 * 비교 엔진(Compare)과 화면은 DBMS 를 모른다. 새 DBMS 는 이 인터페이스를 구현해 Main.ADAPTERS 에 등록한다.
 */
interface Adapter {

    /** 접속해 보고 {version, user, schemas} 를 돌려준다. 실패하면 DB 오류 메시지를 담은 예외. */
    Map<String, Object> test(Map<String, Object> conn) throws Exception;

    /**
     * schema 의 오브젝트를 종류별로 돌려준다 (종류 → 키 → 오브젝트).
     * exclude 에 걸린 테이블·시퀀스는 빼고 그 이름을 excluded 에 담는다.
     */
    Map<String, Map<String, DbObject>> fetch(Map<String, Object> conn, String schema, Pattern exclude,
                                            List<String> excluded) throws Exception;

    /** TO 를 FROM 에 맞추는 SQL. st 는 from(FROM 에만 있음) 또는 mod(양쪽 다름). */
    List<String> sync(String st, DbObject from, DbObject to);

    /** TO 에만 있는 오브젝트를 지우는 SQL. 실행되지 않게 주석으로 낸다. */
    List<String> drop(DbObject to);
}

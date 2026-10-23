package schemadiff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 한 DB 의 오브젝트 하나. 어댑터가 채우고 비교 엔진이 읽는다. */
final class DbObject {
    final String type;   // table, index, constraint, sequence, view, procedure, function, trigger, package, type
    final String name;   // 실제 이름
    String key;          // 양쪽을 짝짓는 키. 보통 이름이고, 시스템 생성 이름이면 구성으로 만든다
    String sig;          // 이름을 뺀 구성. 이름만 다른 짝을 찾을 때 쓴다 (없으면 null)
    String src;          // 원문 비교 키 (뷰·코드). 공백을 정규화한 값
    String summary = "";
    /** 비교하는 속성. 값이 다르면 「변경」. */
    final LinkedHashMap<String, String> attrs = new LinkedHashMap<>();
    /** 보여주기만 하는 속성 (최종 DDL 시각 등). */
    final LinkedHashMap<String, String> info = new LinkedHashMap<>();
    List<String> ddl = new ArrayList<>();
    /** 동기화 SQL 을 만들 때 어댑터가 다시 읽는 원자료. */
    final Map<String, Object> spec = new HashMap<>();

    DbObject(String type, String name) {
        this.type = type;
        this.name = name;
        this.key = name;
    }
}

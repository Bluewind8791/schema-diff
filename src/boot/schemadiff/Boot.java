package schemadiff;

import java.lang.reflect.InvocationTargetException;
import javax.swing.JOptionPane;

/**
 * SchemaDiff.exe 의 진입점. Java 8 에서도 읽히도록 이 파일만 --release 8 로 컴파일한다.
 * Launch4j 는 JAVA_HOME·PATH 에서 찾은 java 의 버전을 확인하지 않으므로, 11 미만이면 여기서 안내하고 끝낸다.
 */
public final class Boot {

    public static void main(String[] args) throws Throwable {
        String v = System.getProperty("java.specification.version"); // 8 이하는 "1.8" 형식
        if (v.startsWith("1.") || Integer.parseInt(v) < 11) {
            JOptionPane.showMessageDialog(null, "SchemaDiff 는 Java 11 이상이 필요합니다.\n"
                    + "지금 찾은 Java: " + v + " (" + System.getProperty("java.home") + ")\n\n"
                    + "Java 11 이상을 설치하거나, JAVA_HOME 을 Java 11 이상의 설치 폴더로 바꾸세요.",
                    "SchemaDiff", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            Class.forName("schemadiff.Main").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            // exe 에는 콘솔이 없어 오류가 보이지 않으므로 창으로 알린다
            Throwable c = e.getCause();
            JOptionPane.showMessageDialog(null, "SchemaDiff 를 시작하지 못했습니다.\n\n" + (c.getMessage() != null ? c.getMessage() : c),
                    "SchemaDiff", JOptionPane.ERROR_MESSAGE);
            throw c;
        }
    }
}

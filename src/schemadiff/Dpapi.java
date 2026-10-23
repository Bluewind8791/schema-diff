package schemadiff;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Windows DPAPI(CurrentUser) 로 비밀번호를 암호화한다. 이 PC 의 이 Windows 계정에서만 풀린다.
 * 추가 라이브러리 없이 Windows PowerShell 의 ProtectedData 를 부른다. 평문은 명령줄이 아니라 표준입력으로 넘긴다.
 */
final class Dpapi {

    static String protect(String plain) throws Exception {
        return run("Protect", Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8)));
    }

    static String unprotect(String enc) throws Exception {
        return new String(Base64.getDecoder().decode(run("Unprotect", enc)), StandardCharsets.UTF_8);
    }

    private static String run(String op, String inputB64) throws Exception {
        String script = "Add-Type -AssemblyName System.Security; $i = [Console]::In.ReadLine(); "
                + "[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::" + op
                + "([Convert]::FromBase64String($i), $null, 'CurrentUser'))";
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true).start();
        try (OutputStream in = p.getOutputStream()) {
            in.write((inputB64 + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        String out = read(p.getInputStream()).trim();
        if (p.waitFor() != 0 || out.isEmpty()) throw new Exception("DPAPI " + op + " 실패: " + out);
        return out;
    }

    private static String read(InputStream s) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        s.transferTo(b);
        return b.toString(StandardCharsets.UTF_8);
    }
}

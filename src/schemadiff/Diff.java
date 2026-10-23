package schemadiff;

import java.util.ArrayList;
import java.util.List;

/** DDL 줄 단위 비교. 공백 차이는 무시하고 빈 줄은 뺀다. */
final class Diff {

    /** 행: [상태(same/mod/from/to), FROM 줄, FROM 줄번호, TO 줄, TO 줄번호]. 줄번호는 원래 줄 기준. */
    static List<List<String>> lines(List<String> a, List<String> b) {
        List<Integer> ia = nonBlank(a), ib = nonBlank(b);
        String[] ka = keys(a, ia), kb = keys(b, ib);
        int n = ka.length, m = kb.length;
        // 앞뒤 같은 줄을 먼저 떼어 LCS 표를 작게 한다
        int pre = 0;
        while (pre < n && pre < m && ka[pre].equals(kb[pre])) pre++;
        int suf = 0;
        while (suf < n - pre && suf < m - pre && ka[n - 1 - suf].equals(kb[m - 1 - suf])) suf++;
        int rn = n - pre - suf, rm = m - pre - suf;
        int[][] L = new int[rn + 1][rm + 1];
        for (int i = rn - 1; i >= 0; i--)
            for (int j = rm - 1; j >= 0; j--)
                L[i][j] = ka[pre + i].equals(kb[pre + j]) ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);

        List<List<String>> out = new ArrayList<>();
        for (int x = 0; x < pre; x++) out.add(row("same", a, ia.get(x), b, ib.get(x)));
        List<Integer> del = new ArrayList<>(), add = new ArrayList<>();
        int i = 0, j = 0;
        while (i < rn || j < rm) {
            if (i < rn && j < rm && ka[pre + i].equals(kb[pre + j])) {
                flush(out, a, b, del, add);
                out.add(row("same", a, ia.get(pre + i), b, ib.get(pre + j)));
                i++;
                j++;
            } else if (j >= rm || (i < rn && L[i + 1][j] >= L[i][j + 1])) {
                del.add(ia.get(pre + i++));
            } else {
                add.add(ib.get(pre + j++));
            }
        }
        flush(out, a, b, del, add);
        for (int x = 0; x < suf; x++) out.add(row("same", a, ia.get(n - suf + x), b, ib.get(m - suf + x)));
        return out;
    }

    /** 바뀐 덩어리의 지운 줄·추가한 줄을 나란히 놓는다. 둘 다 있으면 mod. */
    private static void flush(List<List<String>> out, List<String> a, List<String> b, List<Integer> del, List<Integer> add) {
        for (int x = 0; x < Math.max(del.size(), add.size()); x++) {
            Integer d = x < del.size() ? del.get(x) : null, s = x < add.size() ? add.get(x) : null;
            out.add(row(d != null && s != null ? "mod" : d != null ? "from" : "to", a, d, b, s));
        }
        del.clear();
        add.clear();
    }

    private static List<String> row(String k, List<String> a, Integer ai, List<String> b, Integer bi) {
        return List.of(k, ai == null ? "" : a.get(ai), ai == null ? "" : String.valueOf(ai + 1),
                bi == null ? "" : b.get(bi), bi == null ? "" : String.valueOf(bi + 1));
    }

    private static List<Integer> nonBlank(List<String> l) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) if (!l.get(i).isBlank()) out.add(i);
        return out;
    }

    private static String[] keys(List<String> l, List<Integer> idx) {
        String[] out = new String[idx.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(idx.get(i)).replaceAll("\\s+", " ").trim();
        return out;
    }
}

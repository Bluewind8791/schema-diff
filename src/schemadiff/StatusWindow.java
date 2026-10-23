package schemadiff;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** SchemaDiff.exe 로 띄웠을 때 콘솔 대신 보이는 작은 창. 이 창을 닫으면 SchemaDiff 가 끝난다. */
final class StatusWindow {

    static void show(String url, Runnable openBrowser) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // 기본 모양으로 띄운다
            }
            JFrame f = new JFrame("SchemaDiff");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            JButton open = new JButton("브라우저 열기");
            open.addActionListener(e -> openBrowser.run());
            JButton quit = new JButton("종료");
            quit.addActionListener(e -> System.exit(0));
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            buttons.add(open);
            buttons.add(quit);
            JPanel p = new JPanel(new BorderLayout(0, 12));
            p.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
            p.add(new JLabel("<html>SchemaDiff 실행 중: " + url + "<br>이 창을 닫으면 종료됩니다.</html>"), BorderLayout.CENTER);
            p.add(buttons, BorderLayout.SOUTH);
            f.setContentPane(p);
            f.pack();
            f.setResizable(false);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}

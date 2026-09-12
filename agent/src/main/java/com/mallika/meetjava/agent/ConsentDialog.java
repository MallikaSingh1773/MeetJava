package com.mallika.meetjava.agent;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The native permission prompt.
 *
 * The server can ask for control but cannot grant it. This dialog is the only
 * place a grant is created, and it defaults to "no": if it is ignored it
 * declines itself after a timeout rather than sitting open forever waiting to
 * be clicked by accident.
 */
public class ConsentDialog {

    private static final int TIMEOUT_SECONDS = 30;

    public static boolean ask(String requesterName) {
        final AtomicBoolean accepted = new AtomicBoolean(false);

        JDialog dialog = new JDialog((Frame) null, "Remote control request", true);
        dialog.setAlwaysOnTop(true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));

        JLabel title = new JLabel("<html><b>" + escape(requesterName)
                + "</b> is asking to control this computer.</html>");
        title.setFont(title.getFont().deriveFont(14f));

        JLabel detail = new JLabel("<html><font color='#666'>They will be able to move your mouse, "
                + "type, and open anything on this machine.<br>You can stop it at any time.</font></html>");

        JLabel countdown = new JLabel();
        countdown.setForeground(new Color(120, 120, 120));

        JPanel text = new JPanel(new GridLayout(0, 1, 0, 8));
        text.add(title);
        text.add(detail);
        text.add(countdown);

        JButton allow = new JButton("Allow control");
        JButton deny = new JButton("Decline");
        deny.requestFocusInWindow();   // the safe option is the one under the cursor

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(deny);
        buttons.add(allow);

        allow.addActionListener(e -> { accepted.set(true); dialog.dispose(); });
        deny.addActionListener(e -> dialog.dispose());

        body.add(text, BorderLayout.CENTER);
        body.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(body);
        dialog.pack();
        dialog.setLocationRelativeTo(null);

        // Unanswered means declined.
        final int[] left = { TIMEOUT_SECONDS };
        Timer timer = new Timer(1000, null);
        timer.addActionListener(e -> {
            left[0]--;
            countdown.setText("Declines automatically in " + left[0] + "s");
            if (left[0] <= 0) {
                timer.stop();
                dialog.dispose();
            }
        });
        countdown.setText("Declines automatically in " + left[0] + "s");
        timer.start();

        dialog.setVisible(true);   // blocks until disposed
        timer.stop();
        return accepted.get();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

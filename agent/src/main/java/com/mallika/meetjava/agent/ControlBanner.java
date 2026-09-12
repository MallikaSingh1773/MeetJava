package com.mallika.meetjava.agent;

import javax.swing.*;
import java.awt.*;

/**
 * The always-on-top strip shown while someone else is driving this desktop.
 *
 * Non-negotiable part of the design: control is never invisible. If a machine
 * can be taken over, the person sitting at it must be able to see that it is
 * happening and stop it in one click, without going back to the browser.
 */
public class ControlBanner extends JWindow {

    private final JLabel label = new JLabel();

    public ControlBanner(Runnable onStop) {
        setAlwaysOnTop(true);
        setBackground(new Color(0, 0, 0, 0));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 9));
        bar.setBackground(new Color(176, 32, 38));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        label.setForeground(Color.WHITE);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));

        JButton stop = new JButton("Stop control");
        stop.setFocusable(true);
        stop.addActionListener(e -> onStop.run());

        bar.add(label);
        bar.add(stop);
        add(bar);
        pack();
    }

    public void show(String controllerName) {
        label.setText("  " + controllerName + " is controlling this computer");
        pack();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setLocation((screen.width - getWidth()) / 2, 0);
        setVisible(true);
        toFront();
    }

    public void hideBanner() {
        setVisible(false);
    }
}

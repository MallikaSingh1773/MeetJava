package com.mallika.meetjava.agent;

import javax.swing.*;
import java.awt.*;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * MeetJava desktop agent.
 *
 * Why this exists: a browser tab cannot move an operating system's cursor. So
 * the person who wants to HAND OVER control runs this small tray app. The person
 * TAKING control needs nothing but the browser. Zoom and TeamViewer are built
 * the same way.
 *
 * The safety model, in order:
 *   1. The agent pairs only with a participant already sitting in the meeting.
 *   2. A control request opens a native dialog here; the server cannot grant it.
 *   3. An unanswered dialog declines itself.
 *   4. While control is live, an always-on-top banner says so.
 *   5. Stop works locally, with no server round trip, and releases every key.
 *   6. Every grant is written to the server's audit log.
 */
public class AgentApp {

    private final Preferences prefs = Preferences.userNodeForPackage(AgentApp.class);

    private final JFrame frame = new JFrame("MeetJava Desktop Agent");
    private final JTextField serverField = new JTextField(prefs.get("server", "http://localhost:8080"));
    private final JTextField codeField = new JTextField(prefs.get("code", ""));
    private final JTextField nameField = new JTextField(prefs.get("name", ""));
    private final JLabel statusLabel = new JLabel("Not connected");
    private final JButton connectButton = new JButton("Connect");
    private final JButton stopButton = new JButton("Stop control");

    private ControlClient client;
    private InputReplayer input;
    private ControlBanner banner;

    private volatile String controllerPeerId = null;
    private volatile String controllerName = null;

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> new AgentApp().start());
    }

    private void start() {
        try {
            input = new InputReplayer();
        } catch (AWTException e) {
            JOptionPane.showMessageDialog(null,
                    "This machine does not allow input control:\n" + e.getMessage(),
                    "MeetJava Agent", JOptionPane.ERROR_MESSAGE);
            return;
        }

        banner = new ControlBanner(() -> stopControl("stopped on the host machine"));
        client = new ControlClient(this::onServerMessage, this::setStatus);

        buildUi();
    }

    // ------------------------------------------------------------------ ui

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(16, 18, 10, 18));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        row = addRow(form, c, row, "Server", serverField);
        row = addRow(form, c, row, "Meeting code", codeField);
        row = addRow(form, c, row, "Your name", nameField);

        JLabel hint = new JLabel("<html><font color='#666' size='-1'>"
                + "Join the meeting in your browser first, then connect with the same name.<br>"
                + "Nobody can control this computer unless you accept a request.</font></html>");
        c.gridx = 0; c.gridy = row++; c.gridwidth = 2;
        form.add(hint, c);

        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> stopControl("stopped on the host machine"));
        connectButton.addActionListener(e -> toggleConnection());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(stopButton);
        buttons.add(connectButton);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 22, 10, 18));
        statusLabel.setForeground(new Color(100, 100, 100));

        frame.setLayout(new BorderLayout());
        frame.add(form, BorderLayout.CENTER);
        frame.add(statusLabel, BorderLayout.SOUTH);
        frame.add(buttons, BorderLayout.PAGE_END);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setSize(430, 290);
        frame.setLocationRelativeTo(null);
        frame.setAlwaysOnTop(true);
        frame.setVisible(true);
    }

    private int addRow(JPanel panel, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(field, c);
        return row + 1;
    }

    private void toggleConnection() {
        if (client.isConnected()) {
            stopControl("agent disconnected");
            client.disconnect();
            connectButton.setText("Connect");
            setStatus("Not connected");
            return;
        }
        if (codeField.getText().isBlank() || nameField.getText().isBlank()) {
            setStatus("Enter the meeting code and the name you joined with.");
            return;
        }
        prefs.put("server", serverField.getText());
        prefs.put("code", codeField.getText());
        prefs.put("name", nameField.getText());

        client.connect(serverField.getText(), codeField.getText(), nameField.getText());
        connectButton.setText("Disconnect");
    }

    // ------------------------------------------------------------------ server messages

    private void onServerMessage(Map<String, Object> msg) {
        String type = String.valueOf(msg.get("type"));
        switch (type) {
            case "agent-ready" -> setStatus("Ready. Paired with \""
                    + msg.get("displayName") + "\" in meeting " + msg.get("meetingCode") + ".");

            case "control-request" -> onControlRequest(msg);

            // Every event is gated on the enabled flag inside InputReplayer as
            // well, so a stray packet after a stop changes nothing.
            case "control-input" -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> event = (Map<String, Object>) msg.get("event");
                input.apply(event);
            }

            case "control-end" -> stopControlLocally(String.valueOf(msg.getOrDefault("reason", "ended")));

            case "error" -> setStatus("Server: " + msg.get("message"));

            default -> { }
        }
    }

    private void onControlRequest(Map<String, Object> msg) {
        String from = (String) msg.get("from");
        String fromName = String.valueOf(msg.getOrDefault("fromName", "Someone"));

        if (input.isEnabled()) {
            // Already handing control to someone; do not stack requests.
            respond(from, false);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            boolean accepted = ConsentDialog.ask(fromName);
            respond(from, accepted);

            if (accepted) {
                controllerPeerId = from;
                controllerName = fromName;
                input.setEnabled(true);
                banner.show(fromName);
                Dimension s = input.screenSize();
                setStatus(fromName + " is controlling this computer (" + s.width + "x" + s.height + ").");
                stopButton.setEnabled(true);
            } else {
                setStatus("Declined a control request from " + fromName + ".");
            }
        });
    }

    private void respond(String controllerPeerId, boolean accepted) {
        Map<String, Object> out = ControlClient.message("control-response");
        out.put("to", controllerPeerId);
        out.put("accepted", accepted);
        client.send(out);
    }

    // ------------------------------------------------------------------ stopping

    /**
     * The kill switch.
     *
     * Input is cut here first and the server is told afterwards, so control ends
     * even if the network is slow or already gone.
     */
    private void stopControl(String reason) {
        if (!input.isEnabled() && controllerPeerId == null) return;
        stopControlLocally(reason);
        client.send(ControlClient.message("control-end"));
    }

    private void stopControlLocally(String reason) {
        input.setEnabled(false);          // also releases any held button or modifier
        controllerPeerId = null;
        String who = controllerName == null ? "Control" : controllerName + "'s control";
        controllerName = null;
        SwingUtilities.invokeLater(() -> {
            banner.hideBanner();
            stopButton.setEnabled(false);
            setStatus(who + " ended (" + reason + ").");
        });
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText("<html>" + text + "</html>"));
    }
}

package com.mallika.meetjava.agent;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns normalised remote events into real mouse and keyboard input on this machine.
 *
 * java.awt.Robot is why Java is a good fit for this job: OS-level input injection
 * is in the JDK, no native library needed.
 *
 * Coordinates arrive as fractions of the screen (0.0 to 1.0) rather than pixels.
 * The controller therefore never needs to know this machine's resolution, DPI
 * scaling or monitor layout, and a 900px wide video can drive a 4K desktop.
 */
public class InputReplayer {

    private final Robot robot;
    private final Rectangle screen;

    /** Hard gate. Nothing is replayed unless control is actually granted right now. */
    private volatile boolean enabled = false;

    public InputReplayer() throws AWTException {
        this.robot = new Robot();
        this.robot.setAutoDelay(0);
        this.screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration()
                .getBounds();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) releaseEverything();
    }

    public boolean isEnabled() { return enabled; }

    public Dimension screenSize() {
        return new Dimension(screen.width, screen.height);
    }

    public void apply(Map<String, Object> event) {
        if (!enabled || event == null) return;
        String kind = String.valueOf(event.get("kind"));
        try {
            switch (kind) {
                case "move"  -> move(event);
                case "down"  -> { move(event); robot.mousePress(buttonMask(intOf(event.get("button")))); }
                case "up"    -> { move(event); robot.mouseRelease(buttonMask(intOf(event.get("button")))); }
                case "wheel" -> { move(event); robot.mouseWheel(intOf(event.get("dy"))); }
                case "key"   -> key(event);
                default      -> { }
            }
        } catch (Exception e) {
            // One bad event must never kill the agent; the session keeps going.
            System.err.println("input ignored: " + e.getMessage());
        }
    }

    private void move(Map<String, Object> event) {
        Object rawX = event.get("x"), rawY = event.get("y");
        if (rawX == null || rawY == null) return;
        double nx = clamp(((Number) rawX).doubleValue());
        double ny = clamp(((Number) rawY).doubleValue());
        robot.mouseMove(screen.x + (int) Math.round(nx * screen.width),
                        screen.y + (int) Math.round(ny * screen.height));
    }

    private void key(Map<String, Object> event) {
        String key = String.valueOf(event.getOrDefault("key", ""));
        if (key.isEmpty() || "Unidentified".equals(key)) return;

        boolean ctrl  = Boolean.TRUE.equals(event.get("ctrl"));
        boolean alt   = Boolean.TRUE.equals(event.get("alt"));
        boolean shift = Boolean.TRUE.equals(event.get("shift"));

        int keyCode;
        if (key.length() == 1) {
            char c = key.charAt(0);
            keyCode = KeyEvent.getExtendedKeyCodeForChar(c);
            // A capital letter or a shifted symbol needs Shift held, even when the
            // browser did not report the modifier separately.
            if (Character.isUpperCase(c) || "~!@#$%^&*()_+{}|:\"<>?".indexOf(c) >= 0) shift = true;
        } else {
            Integer mapped = NAMED_KEYS.get(key);
            if (mapped == null) return;
            keyCode = mapped;
        }
        if (keyCode == KeyEvent.VK_UNDEFINED) return;

        // Modifiers down, key tapped, modifiers up. Doing it per keystroke keeps
        // the remote machine from getting stuck with Ctrl held if a packet is lost.
        if (ctrl)  robot.keyPress(KeyEvent.VK_CONTROL);
        if (alt)   robot.keyPress(KeyEvent.VK_ALT);
        if (shift) robot.keyPress(KeyEvent.VK_SHIFT);
        try {
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
        } finally {
            if (shift) robot.keyRelease(KeyEvent.VK_SHIFT);
            if (alt)   robot.keyRelease(KeyEvent.VK_ALT);
            if (ctrl)  robot.keyRelease(KeyEvent.VK_CONTROL);
        }
    }

    /** Called whenever control stops, so no button or modifier is left pressed. */
    private void releaseEverything() {
        try {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON2_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            robot.keyRelease(KeyEvent.VK_ALT);
            robot.keyRelease(KeyEvent.VK_SHIFT);
        } catch (Exception ignored) {
            // Releasing something that was never pressed is fine.
        }
    }

    private static int buttonMask(int browserButton) {
        return switch (browserButton) {
            case 1 -> InputEvent.BUTTON2_DOWN_MASK;  // middle
            case 2 -> InputEvent.BUTTON3_DOWN_MASK;  // right
            default -> InputEvent.BUTTON1_DOWN_MASK; // left
        };
    }

    private static int intOf(Object o) { return o instanceof Number n ? n.intValue() : 0; }

    private static double clamp(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /** Browser KeyboardEvent.key values that are not single characters. */
    private static final Map<String, Integer> NAMED_KEYS = new HashMap<>();
    static {
        NAMED_KEYS.put("Enter", KeyEvent.VK_ENTER);
        NAMED_KEYS.put("Backspace", KeyEvent.VK_BACK_SPACE);
        NAMED_KEYS.put("Tab", KeyEvent.VK_TAB);
        NAMED_KEYS.put("Delete", KeyEvent.VK_DELETE);
        NAMED_KEYS.put("ArrowUp", KeyEvent.VK_UP);
        NAMED_KEYS.put("ArrowDown", KeyEvent.VK_DOWN);
        NAMED_KEYS.put("ArrowLeft", KeyEvent.VK_LEFT);
        NAMED_KEYS.put("ArrowRight", KeyEvent.VK_RIGHT);
        NAMED_KEYS.put("Home", KeyEvent.VK_HOME);
        NAMED_KEYS.put("End", KeyEvent.VK_END);
        NAMED_KEYS.put("PageUp", KeyEvent.VK_PAGE_UP);
        NAMED_KEYS.put("PageDown", KeyEvent.VK_PAGE_DOWN);
        NAMED_KEYS.put("Insert", KeyEvent.VK_INSERT);
        NAMED_KEYS.put("CapsLock", KeyEvent.VK_CAPS_LOCK);
        for (int i = 1; i <= 12; i++) {
            NAMED_KEYS.put("F" + i, KeyEvent.VK_F1 + (i - 1));
        }
        // Escape is deliberately absent: it is the controller's own exit key and
        // is never forwarded to this machine.
    }
}

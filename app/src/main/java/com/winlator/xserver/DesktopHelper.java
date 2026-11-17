package com.winlator.xserver;

import androidx.collection.ArrayMap;

import java.util.Map;

import timber.log.Timber;

public abstract class DesktopHelper {
    /** The window this helper last made the active one, so its going away can be told from any other window's. */
    private static Window lastActivated;
    private static long lastPressLogMs;

    public static void attachTo(final XServer xServer) {
        setupXResources(xServer);

        xServer.pointer.addOnPointerMotionListener(new Pointer.OnPointerMotionListener() {
            @Override
            public void onPointerButtonPress(Pointer.Button button) {
                updateFocusedWindow(xServer);
            }
        });

        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onMapWindow(Window window) {
                setFocusedWindow(xServer, window, "mapped");
            }

            @Override
            public void onUnmapWindow(Window window) {
                activateAfterClose(xServer, window);
            }
        });
    }

    private static void updateFocusedWindow(XServer xServer) {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
            Window focusedWindow = xServer.windowManager.getFocusedWindow();
            Window child = xServer.windowManager.findPointWindow(xServer.pointer.getClampedX(), xServer.pointer.getClampedY());
            logPress(xServer, child, focusedWindow);
            if (child == null && focusedWindow != xServer.windowManager.rootWindow) {
                xServer.windowManager.setFocus(xServer.windowManager.rootWindow, WindowManager.FocusRevertTo.NONE);
            }
            else if (child != null && child != focusedWindow) {
                setFocusedWindow(xServer, child, "pressed");
            }
        }
    }

    private static void setFocusedWindow(XServer xServer, Window window, String reason) {
        if (window.isApplicationWindow()) {
            boolean parentIsRoot = window.getParent() == xServer.windowManager.rootWindow;
            xServer.windowManager.setFocus(window, parentIsRoot ? WindowManager.FocusRevertTo.POINTER_ROOT : WindowManager.FocusRevertTo.PARENT);
            xServer.getWinHandler().bringToFront(window.getClassName(), window.getHandle());
            lastActivated = window;
            Timber.i("Active window (%s): %s", reason, describe(window));
        }
    }

    /**
     * A window that goes away leaves the next one inactive: Windows programs are only made active when a window is
     * mapped or pressed (above), so after a dialog, a settings window or the game closed, the window behind it stayed
     * dimmed or showed its last picture until it was tapped. This does what that tap did: when the active window is
     * unmapped, the topmost application window left becomes the active one.
     */
    private static void activateAfterClose(XServer xServer, Window closed) {
        Window root = xServer.windowManager.rootWindow;
        Window focused = xServer.windowManager.getFocusedWindow();
        boolean activeWentAway = closed == lastActivated || focused == null || focused == root || !focused.isApplicationWindow();
        if (!activeWentAway) return;
        if (closed == lastActivated) lastActivated = null;

        Window next = focused != null && focused != root && focused != closed && focused.isApplicationWindow() ? focused : null;
        if (next == null) {
            java.util.List<Window> children = root.getChildren();
            for (int i = children.size() - 1; i >= 0 && next == null; i--) {
                Window child = children.get(i);
                if (child != closed && child.isApplicationWindow()) next = child;
            }
        }
        // Start-up maps and unmaps many helper windows; a window this helper already made active is left alone.
        if (next != null && next != lastActivated) setFocusedWindow(xServer, next, "the active window closed");
    }

    /** One line per press, at most one a second: where the press landed and which window has the focus. */
    private static void logPress(XServer xServer, Window pointWindow, Window focusedWindow) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastPressLogMs < 1000) return;
        lastPressLogMs = now;
        Timber.i("Press at %d,%d: on %s, focus %s, pointer grab %s", xServer.pointer.getClampedX(), xServer.pointer.getClampedY(), describe(pointWindow), describe(focusedWindow), describe(xServer.grabManager.getWindow()));
    }

    private static String describe(Window window) {
        if (window == null) return "none";
        return "id=" + window.id + " class=" + window.getClassName() + " app=" + window.isApplicationWindow() + " enabled=" + window.attributes.isEnabled();
    }

    private static void setupXResources(XServer xServer) {
        int atom = Atom.getId("RESOURCE_MANAGER");
        int type = Atom.getId("STRING");

        ArrayMap<String, String> values = new ArrayMap<>();
        values.put("size", "20");
        values.put("theme", "dmz");
        values.put("theme_core", "true");

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            sb.append("Xcursor")
                    .append('.')
                    .append(entry.getKey())
                    .append(':')
                    .append('\t')
                    .append(entry.getValue())
                    .append('\n');
        }

        byte[] data = sb.toString().getBytes(XServer.LATIN1_CHARSET);
        xServer.windowManager.rootWindow.modifyProperty(atom, type, Property.Format.BYTE_ARRAY, Property.Mode.APPEND, data);
    }
}
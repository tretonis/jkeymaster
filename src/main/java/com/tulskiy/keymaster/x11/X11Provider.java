package com.tulskiy.keymaster.x11;

import com.tulskiy.keymaster.common.HotKey;
import com.tulskiy.keymaster.common.MediaKey;
import com.tulskiy.keymaster.common.Provider;

import javax.swing.*;
import java.awt.event.ActionListener;
import java.util.*;

import static com.tulskiy.keymaster.x11.LibX11.*;
import static com.tulskiy.keymaster.x11.KeySymDef.*;

/**
 * Author: Denis Tulskiy
 * Date: 6/13/11
 */
public class X11Provider extends Provider {
    private Display display;
    private Window window;
    private boolean listening;
    private Thread thread;
    private boolean reset;
    private ErrorHandler errorHandler;
    private final Object lock = new Object();
    private Deque<X11HotKey> registerQueue = new LinkedList<X11HotKey>();
    private List<X11HotKey> hotKeys = new ArrayList<X11HotKey>();

    public void init() {
        Runnable runnable = new Runnable() {
            public void run() {
                logger.info("Starting X11 global hotkey provider");
                display = XOpenDisplay(null);
                errorHandler = new ErrorHandler();
                XSetErrorHandler(errorHandler);
                window = XDefaultRootWindow(display);
                listening = true;
                XEvent event = new XEvent();

                while (listening) {
                    while (XPending(display) > 0) {
                        XNextEvent(display, event);
                        if (event.type == KeyPress) {
                            XKeyEvent xkey = (XKeyEvent) event.readField("xkey");
                            for (X11HotKey hotKey : hotKeys) {
                                int state = xkey.state & (ShiftMask | ControlMask | Mod1Mask | Mod4Mask);
                                if (hotKey.code == (byte) xkey.keycode && hotKey.modifiers == state) {
                                    logger.info("Received event for hotkey: " + hotKey);
                                    fireEvent(hotKey);
                                    break;
                                }
                            }
                        }
                    }

                    synchronized (lock) {
                        if (reset) {
                            logger.info("Reset hotkeys");
                            resetAll();
                            reset = false;
                            lock.notify();
                        }

                        while (!registerQueue.isEmpty()) {
                            X11HotKey hotKey = registerQueue.poll();
                            logger.info("Registering hotkey: " + hotKey);
                            if (hotKey.isMedia()) {
                                registerMedia(hotKey);
                            } else {
                                register(hotKey);
                            }
                            hotKeys.add(hotKey);
                        }

                        try {
                            lock.wait(300);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                }

                logger.info("Thread - stop listening");
            }
        };

        thread = new Thread(runnable);
        thread.start();
    }

    private void register(X11HotKey hotKey) {
        byte code = KeyMap.getCode(hotKey.keyStroke, display);
        if (code == 0) {
            return;
        }
        int modifiers = KeyMap.getModifiers(hotKey.keyStroke);
        hotKey.code = code;
        hotKey.modifiers = modifiers;
        for (int i = 0; i < 16; i++) {
            int flags = correctModifiers(modifiers, i);

            XGrabKey(display, code, flags, window, true, GrabModeAsync, GrabModeAsync);
        }
    }

    private void resetAll() {
        for (X11HotKey hotKey : hotKeys) {
            if (!hotKey.isMedia()) {
                int modifiers = hotKey.modifiers;
                for (int i = 0; i < 16; i++) {
                    int flags = correctModifiers(modifiers, i);

                    XUngrabKey(display, hotKey.code, flags, window);
                }
            } else {
                XUngrabKey(display, hotKey.code, 0, window);
            }
        }

        hotKeys.clear();
    }

    private int correctModifiers(int modifiers, int flags) {
        int ret = modifiers;
        if ((flags & 1) != 0)
            ret |= LockMask;
        if ((flags & 2) != 0)
            ret |= Mod2Mask;
        if ((flags & 4) != 0)
            ret |= Mod3Mask;
        if ((flags & 8) != 0)
            ret |= Mod5Mask;
        return ret;
    }

    private void registerMedia(X11HotKey hotKey) {
        int code = 0;
        switch (hotKey.mediaKey) {
            case MEDIA_NEXT_TRACK:
                code = XF86XK_AudioNext;
                break;
            case MEDIA_PLAY_PAUSE:
                code = XF86XK_AudioPlay;
                break;
            case MEDIA_PREV_TRACK:
                code = XF86XK_AudioPrev;
                break;
            case MEDIA_STOP:
                code = XF86XK_AudioStop;
                break;
        }
        hotKey.modifiers = 0;
        byte keyCode = XKeysymToKeycode(display, code);
        hotKey.code = keyCode;
        XGrabKey(display, keyCode, 0, window, true, GrabModeAsync, GrabModeAsync);
    }

    public void stop() {
        if (thread != null) {
            listening = false;
            try {
                thread.join();
                XCloseDisplay(display);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void register(KeyStroke keyCode, ActionListener listener) {
        synchronized (lock) {
            registerQueue.add(new X11HotKey(keyCode, listener));
        }
    }

    public void register(MediaKey mediaKey, ActionListener listener) {
        synchronized (lock) {
            registerQueue.add(new X11HotKey(mediaKey, listener));
        }
    }

    public void reset() {
        synchronized (lock) {
            reset = true;
            try {
                lock.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    class ErrorHandler implements XErrorHandler {
        public int apply(Display display, XErrorEvent errorEvent) {
            byte[] buf = new byte[1024];
            XGetErrorText(display, errorEvent.error_code, buf, buf.length);
            int len = 0;
            while (buf[len] != 0) len++;
            logger.warning("Error: " + new String(buf, 0, len));
            return 0;
        }
    }

    class X11HotKey extends HotKey {
        byte code;
        int modifiers;

        X11HotKey(KeyStroke keyStroke, ActionListener listener) {
            super(keyStroke, listener);
        }

        X11HotKey(MediaKey mediaKey, ActionListener listener) {
            super(mediaKey, listener);
        }
    }
}

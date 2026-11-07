package top.niunaijun.blackboxa.runner;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * A do-nothing service that is declared inside every virtual-app stub process (":p0", ":p1", ...).
 * The host binds to it with BIND_IMPORTANT, which tells Android that the process hosting a
 * virtual app is as important as whatever the host is doing, so it is not cached or killed.
 *
 * It is bind-only on purpose. An earlier version started it as a foreground service, but the
 * engine does not let services run in virtual-app processes, so startForeground() was never
 * called and Android killed the process after its foreground-start timeout. That killed the
 * virtual app and made it reload from scratch.
 */
public class GuestKeepService extends Service {

    private final IBinder binder = new Binder();

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public static class P0 extends GuestKeepService {
    }

    public static class P1 extends GuestKeepService {
    }

    public static class P2 extends GuestKeepService {
    }

    public static class P3 extends GuestKeepService {
    }

    public static class P4 extends GuestKeepService {
    }

    public static class P5 extends GuestKeepService {
    }

    public static class P6 extends GuestKeepService {
    }

    public static class P7 extends GuestKeepService {
    }

    public static class P8 extends GuestKeepService {
    }

    public static class P9 extends GuestKeepService {
    }

    public static class P10 extends GuestKeepService {
    }

    public static class P11 extends GuestKeepService {
    }

    public static class P12 extends GuestKeepService {
    }

    public static class P13 extends GuestKeepService {
    }

    public static class P14 extends GuestKeepService {
    }

    public static class P15 extends GuestKeepService {
    }

    public static class P16 extends GuestKeepService {
    }

    public static class P17 extends GuestKeepService {
    }

    public static class P18 extends GuestKeepService {
    }

    public static class P19 extends GuestKeepService {
    }

    public static class P20 extends GuestKeepService {
    }

    public static class P21 extends GuestKeepService {
    }

    public static class P22 extends GuestKeepService {
    }

    public static class P23 extends GuestKeepService {
    }

    public static class P24 extends GuestKeepService {
    }

    public static class P25 extends GuestKeepService {
    }

    public static class P26 extends GuestKeepService {
    }

    public static class P27 extends GuestKeepService {
    }

    public static class P28 extends GuestKeepService {
    }

    public static class P29 extends GuestKeepService {
    }

    public static class P30 extends GuestKeepService {
    }

    public static class P31 extends GuestKeepService {
    }

    public static class P32 extends GuestKeepService {
    }

    public static class P33 extends GuestKeepService {
    }

    public static class P34 extends GuestKeepService {
    }

    public static class P35 extends GuestKeepService {
    }

    public static class P36 extends GuestKeepService {
    }

    public static class P37 extends GuestKeepService {
    }

    public static class P38 extends GuestKeepService {
    }

    public static class P39 extends GuestKeepService {
    }

    public static class P40 extends GuestKeepService {
    }

    public static class P41 extends GuestKeepService {
    }

    public static class P42 extends GuestKeepService {
    }

    public static class P43 extends GuestKeepService {
    }

    public static class P44 extends GuestKeepService {
    }

    public static class P45 extends GuestKeepService {
    }

    public static class P46 extends GuestKeepService {
    }

    public static class P47 extends GuestKeepService {
    }

    public static class P48 extends GuestKeepService {
    }

    public static class P49 extends GuestKeepService {
    }
}

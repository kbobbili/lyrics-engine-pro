package dev.kbobbili.xitelyrics;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Android doesn't rebind an accessibility service after its app is
 * force-stopped. PlaybackWatcher (a notification listener) does come back,
 * so it calls this to remove and re-add only our own entry in
 * enabled_accessibility_services, which makes the system bind it again.
 * Every other enabled service (e.g. AT4K) is kept as-is.
 *
 * Needs: pm grant dev.kbobbili.xitelyrics android.permission.WRITE_SECURE_SETTINGS
 */
final class A11yRebinder {
    private static final long MIN_INTERVAL_MS = 60_000;
    private static long lastRt = -MIN_INTERVAL_MS;

    private A11yRebinder() {}

    static synchronized void rebindIfNeeded(Context ctx) {
        if (State.a11yConnected) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRt < MIN_INTERVAL_MS) return;
        lastRt = now;
        if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                != PackageManager.PERMISSION_GRANTED) {
            Events.log("a11y_rebind_skipped", "why", "no WRITE_SECURE_SETTINGS");
            return;
        }
        String me = ctx.getPackageName() + "/" + LyricsAccessibilityService.class.getName();
        ContentResolver cr = ctx.getContentResolver();
        String key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String current = Settings.Secure.getString(cr, key);
        StringBuilder others = new StringBuilder();
        if (current != null) {
            for (String s : current.split(":")) {
                if (s.isEmpty() || s.equals(me)) continue;
                if (others.length() > 0) others.append(':');
                others.append(s);
            }
        }
        String without = others.toString();
        Settings.Secure.putString(cr, key, without);
        Settings.Secure.putString(cr, key, without.isEmpty() ? me : without + ":" + me);
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Events.log("a11y_rebind", "services", without.isEmpty() ? me : without + ":" + me);
    }
}

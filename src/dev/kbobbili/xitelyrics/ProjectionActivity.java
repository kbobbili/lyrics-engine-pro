package dev.kbobbili.xitelyrics;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * Invisible activity that asks for a MediaProjection token and hands it to
 * CaptureService. The service is started from here, while this activity is
 * in front, so background foreground-service limits don't apply.
 *
 * "ms" in the projection_result event is how long the consent step took:
 * a few hundred ms means it was auto-approved (PROJECT_MEDIA app-op),
 * seconds means a dialog was shown and someone pressed a button.
 */
public class ProjectionActivity extends Activity {
    static final String EXTRA_CALLER = "caller";
    private static final int REQ = 1;
    private long requestedRt;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Events.init(this);
        if (saved != null) return; // recreated while the consent dialog was up
        if (State.captureRunning) {
            Events.log("projection_skip", "why", "already_running");
            finish();
            return;
        }
        requestedRt = SystemClock.elapsedRealtime();
        State.projectionRequestRt = requestedRt;
        Events.log("projection_request", "caller", getIntent().getStringExtra(EXTRA_CALLER));
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
    }

    @Override
    protected void onActivityResult(int req, int code, Intent data) {
        long ms = SystemClock.elapsedRealtime() - requestedRt;
        State.projectionRequestRt = 0;
        boolean ok = code == RESULT_OK && data != null;
        Events.log("projection_result", "ok", ok, "ms", ms);
        if (ok) {
            State.consentDenied = false;
            Intent i = new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_CODE, code)
                    .putExtra(CaptureService.EXTRA_DATA, data)
                    .putExtra(CaptureService.EXTRA_MIC,
                            getIntent().getBooleanExtra(CaptureService.EXTRA_MIC, false));
            startForegroundService(i);
        } else {
            State.consentDenied = true;
        }
        finish();
    }
}

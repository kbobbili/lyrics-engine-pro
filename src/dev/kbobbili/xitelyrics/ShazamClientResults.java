package dev.kbobbili.xitelyrics;

import dev.kbobbili.xitelyrics.recognition.ShazamClient;

/** Results the app synthesises when no clip could be recorded. */
final class ShazamClientResults {
    private ShazamClientResults() {}

    static ShazamClient.Result noCapture() {
        return ShazamClient.parse("not json: no capture", 0, 0);   // -> ERROR, retried by the engine
    }
}

import Toybox.Application;
import Toybox.WatchUi;

// App shell for the "2 Circles" watch face. Watch faces are single-view
// AppBase apps: getInitialView returns the WatchFace. No settings, no
// background service (faithful to the original "No settings" design).
class TwoCirclesApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function onStart(state as Lang.Dictionary?) as Void {
    }

    function onStop(state as Lang.Dictionary?) as Void {
    }

    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        return [ new TwoCirclesView() ];
    }
}

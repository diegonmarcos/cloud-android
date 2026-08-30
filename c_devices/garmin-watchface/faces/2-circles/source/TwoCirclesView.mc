import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;
import Toybox.Time;
import Toybox.WatchUi;

// "2 Circles" watch face renderer.
//
// Model (see faces/2-circles/design.json::geometry for the prose + every knob):
//   The round screen is the MINUTE scale (12-o'clock = :00, clockwise, full
//   turn = 60 min). The current minute -> an angle theta_min. Each circle in
//   DesignConfig.CIRCLES is drawn relative to screen-centre C and a time-driven
//   angle: its centre sits at `center_distance_pct * R` from C along that angle,
//   with radius `radius_pct * R`. Optional rim ticks + an internal hand (e.g.
//   the small HOURS circle carries a short hand pointing to the current hour).
//
// All numbers come from DesignConfig, which build.sh generates from design.json
// (data-driven: tune the JSON, rebuild — never edit constants in this file).
class TwoCirclesView extends WatchUi.WatchFace {

    private var _w as Number = 0;
    private var _h as Number = 0;
    private var _cx as Float = 0.0;
    private var _cy as Float = 0.0;
    private var _r as Float = 0.0;

    function initialize() {
        WatchFace.initialize();
    }

    function onLayout(dc as Graphics.Dc) as Void {
        _w = dc.getWidth();
        _h = dc.getHeight();
        _cx = _w / 2.0;
        _cy = _h / 2.0;
        // Screen radius = half the smaller dimension (round display => equal).
        _r = (_w < _h ? _w : _h) / 2.0;
    }

    // Fraction (0.0..1.0) around the dial for each time unit. Smoothed by the
    // finer unit so motion is continuous, like the original's sweeping look.
    private function minuteFraction(now as Time.Gregorian.Info) as Float {
        return (now.min + now.sec / 60.0) / 60.0;
    }

    private function hourFraction(now as Time.Gregorian.Info) as Float {
        return ((now.hour % 12) + now.min / 60.0) / 12.0;
    }

    // 12-o'clock = 0, clockwise-positive -> radians for trig with screen axes
    // (y grows downward, so the unit vector is (sin, -cos)).
    private function fractionToRadians(frac as Float) as Float {
        return frac * 2.0 * Math.PI;
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var bg = DesignConfig.BACKGROUND_COLOR;
        dc.setColor(bg, bg);
        dc.clear();

        if (DesignConfig.ANTIALIAS && (dc has :setAntiAlias)) {
            dc.setAntiAlias(true);
        }
        dc.setColor(DesignConfig.STROKE_COLOR, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(DesignConfig.STROKE_WIDTH_PX);

        var now = Time.Gregorian.info(Time.now(), Time.FORMAT_SHORT);
        var fMin = minuteFraction(now);
        var fHour = hourFraction(now);

        // Each circle spec: [angleSourceIsHours, centerDistPct, radiusPct,
        //                    ticksOn, tickCount, tickLenPct,
        //                    handOn, handSourceIsHours, handLenPct]
        var circles = DesignConfig.CIRCLES;
        for (var i = 0; i < circles.size(); i++) {
            drawCircle(dc, circles[i], fMin, fHour);
        }
    }

    private function drawCircle(
        dc as Graphics.Dc,
        spec as Array,
        fMin as Float,
        fHour as Float
    ) as Void {
        var angleFrac = spec[0] ? fHour : fMin;       // orbit angle source
        var theta = fractionToRadians(angleFrac);
        var ux = Math.sin(theta);
        var uy = -Math.cos(theta);

        var dist = spec[1] * _r;                       // centre offset from C
        var radius = spec[2] * _r;
        var cx = _cx + ux * dist;
        var cy = _cy + uy * dist;

        dc.drawCircle(cx, cy, radius);

        // Rim ticks (mini dial), evenly spaced, pointing inward from the rim.
        if (spec[3]) {
            var count = spec[4];
            var tickLen = spec[5] * _r;
            for (var t = 0; t < count; t++) {
                var ta = (t * 2.0 * Math.PI) / count;
                var tsx = Math.sin(ta);
                var tcy = -Math.cos(ta);
                var ox = cx + tsx * radius;
                var oy = cy + tcy * radius;
                var ix = cx + tsx * (radius - tickLen);
                var iy = cy + tcy * (radius - tickLen);
                dc.drawLine(ox, oy, ix, iy);
            }
        }

        // Internal hand (e.g. the hour hand inside the small HOURS circle).
        if (spec[6]) {
            var hFrac = spec[7] ? fHour : fMin;
            var ha = fractionToRadians(hFrac);
            var hlen = spec[8] * _r;
            var hx = cx + Math.sin(ha) * hlen;
            var hy = cy - Math.cos(ha) * hlen;
            dc.drawLine(cx, cy, hx, hy);
        }
    }

    function onEnterSleep() as Void {
    }

    function onExitSleep() as Void {
    }
}

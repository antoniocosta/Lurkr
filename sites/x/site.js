// Logged out, X's video player is a trap: taps on it go to "open the app" links (…?launch_app_store=true, to the
// Play Store), and fullscreen and seeking don't work. Lurkr gives every video the browser's own controls (play,
// seek, volume, fullscreen), hides X's overlay on top of it, and turns X's "open the app" links into plain text.
window.__lurkrSite = function () {
  document.querySelectorAll("a[href*='launch_app_store']").forEach(function (a) {
    a.removeAttribute("href");
    a.style.cursor = "default";
  });
  document.querySelectorAll("video").forEach(function (v) {
    if (v.__lurkrPlayer) return;
    v.__lurkrPlayer = true;
    v.controls = true;
    v.setAttribute("playsinline", "");
    // Taps on the video are the browser controls' alone, not X's (which would pause it, or open the app)
    v.addEventListener("click", function (e) { e.stopPropagation(); });
    v.addEventListener("pointerup", function (e) { e.stopPropagation(); });
    // X's player box: the nearest ancestor that also holds X's own buttons (Pause, Mute …)
    var box = v.parentElement;
    for (var i = 0; i < 6 && box && !box.querySelector("button"); i++) box = box.parentElement;
    if (!box) return;
    box.querySelectorAll("*").forEach(function (el) {
      if (el === v || el.contains(v)) {
        el.style.setProperty("pointer-events", "auto", "important");
      } else {
        el.style.setProperty("visibility", "hidden", "important");
        el.style.setProperty("pointer-events", "none", "important");
      }
    });
  });
};

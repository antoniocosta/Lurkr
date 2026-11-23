// YouTube's mobile site. Normal videos get the browser's own player, as on X (play, seek, volume, fullscreen), with
// YouTube's controls hidden; Shorts keep Lurkr's buttons like the other sites' short videos. Video ads are skipped
// the moment they start (sound off, straight to the end, the skip button pressed), then the video goes on.
window.__lurkrSite = function () {
  // Its "Open App" button (only the button: the top bar with search stays)
  document.querySelectorAll("ytm-mobile-topbar-renderer a, ytm-mobile-topbar-renderer button").forEach(function (b) {
    if (/^open app$/i.test((b.textContent || "").trim())) hide(b.closest("ytm-button-renderer") || b, "open app");
  });
  var player = document.querySelector("#movie_player");
  if (!player) return;
  var v = player.querySelector("video");
  if (!v) return;
  if (location.pathname === "/watch" && !v.__lurkrPlayer) {
    v.__lurkrPlayer = true;
    // YouTube keeps switching them off: they stay on
    Object.defineProperty(v, "controls", { configurable: true, get: function () { return true; }, set: function () {} });
    [v.__lurkrButtons, v.__lurkrBar].forEach(function (el) { if (el) el.remove(); });
    v.setAttribute("playsinline", "");
    // Taps on the video are the browser controls' alone
    ["click", "pointerup", "touchend"].forEach(function (type) { v.addEventListener(type, function (e) { e.stopPropagation(); }); });
  }
  if (v.__lurkrPlayer) {
    if (!v.hasAttribute("controls")) v.setAttribute("controls", "");
    // Everything of YouTube's over the video: its controls, title, overlays
    player.querySelectorAll(":scope > *").forEach(function (el) {
      if (el.contains(v)) return;
      el.style.setProperty("display", "none", "important");
    });
    document.querySelectorAll("#player-control-overlay, ytm-custom-control").forEach(function (el) {
      el.style.setProperty("display", "none", "important");
    });
  }
  if (player.classList.contains("ad-showing") || player.classList.contains("ad-interrupting")) {
    if (!v.muted) { v.muted = true; player.__lurkrAdMuted = true; }
    if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration;
    player.querySelectorAll(".ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button").forEach(function (b) { b.click(); });
  } else if (player.__lurkrAdMuted) {
    player.__lurkrAdMuted = false;
    v.muted = false;
  }
};

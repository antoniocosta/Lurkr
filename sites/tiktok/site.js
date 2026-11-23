  // TikTok's embed player fails on a first visit with no cookies ("Player error … check your network
  // connection") and works once reloaded, with the cookies (msToken) the failed load set. So: reload it,
  // at most twice per link.
  window.__lurkrSite = function () {
    if (location.pathname.indexOf("/player/") !== 0) return;
    if (!/player error/i.test(document.body.innerText || "")) return;
    var tries = 0;
    try { tries = Number(sessionStorage.getItem("lurkr-retries") || 0); } catch (e) {}
    if (tries >= 2) return;
    try { sessionStorage.setItem("lurkr-retries", String(tries + 1)); } catch (e) {}
    location.reload();
  };

// Facebook's logged-out reel and post pages, built from generated class names (wbloks_…), so by what they say.
window.__lurkrSite = function () {
  // Its own "Tap to unmute" pill (Lurkr's buttons give sound)
  document.querySelectorAll("div").forEach(function (el) {
    if (el.childElementCount > 3 || !/^tap to unmute$/i.test((el.innerText || "").trim())) return;
    var pill = el;
    while (pill.parentElement && pill.parentElement.getBoundingClientRect().width < 300 &&
           /^tap to unmute$/i.test((pill.parentElement.innerText || "").trim())) pill = pill.parentElement;
    hide(pill, "unmute pill");
  });
  // "More to explore" after a video ends: the whole layer it's on (Lurkr's rewind button takes its place)
  document.querySelectorAll("div, span, h2, h3").forEach(function (el) {
    if (el.childElementCount > 2 || !/^more to explore$/i.test((el.innerText || "").trim())) return;
    for (var n = el.parentElement; n && n !== document.body; n = n.parentElement) {
      var pos = getComputedStyle(n).position;
      if (pos === "absolute" || pos === "fixed") { hide(n, "more to explore"); return; }
    }
  });
  // "Shared by <friend>. See more like this from friends in the app. Connect with friends": the whole card
  document.querySelectorAll("h3[aria-label^='Shared by'], [aria-label^='Shared by']").forEach(function (h) {
    var card = h;
    for (var n = h.parentElement; n && n !== document.body; n = n.parentElement) {
      if (n.getBoundingClientRect().height > 320) break;
      card = n;
      if (/connect with friends/i.test(n.innerText || "")) break;
    }
    hide(card, "shared by");
  });
};

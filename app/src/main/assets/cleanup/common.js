// Lurkr's shared page cleanup, injected at document start into every frame of a site (see Cleanup in Web.kt).
// Filled in per site from its folder in sites/: "__SITE__" (the id), "__PAGE__" (site.json's "page" settings),
// "__FILTERS__" (filters.txt) and the SITE_JS marker (site.js). On other websites shared to Lurkr they're empty.
(function () {
  if (window.__lurkr) return;
  window.__lurkr = true;

  var SITE = "__SITE__";
  var PAGE = "__PAGE__";
  var FILTERS = "__FILTERS__";

  // filters.txt: cosmetic rules in uBlock Origin / AdGuard syntax. "##selector" hides on every page of the site,
  // "host1,host2##selector" only on those hosts (and their subdomains), "~host##selector" everywhere but there.
  // Comments ("!", "[…]"), exceptions ("#@#") and scriptlets ("##+js(…)") are skipped. One CSS rule per selector,
  // so a selector this WebView doesn't understand only drops itself.
  function filterCss() {
    var host = location.hostname, css = "";
    function on(d) { return host === d || host.slice(-d.length - 1) === "." + d; }
    FILTERS.split("\n").forEach(function (line) {
      line = line.trim();
      var i = line.indexOf("##");
      if (!line || line[0] === "!" || line[0] === "[" || i < 0 || line.indexOf("#@#") >= 0) return;
      var sel = line.slice(i + 2).trim();
      if (!sel || sel.indexOf("+js(") === 0) return;
      var doms = line.slice(0, i).split(",").map(function (d) { return d.trim(); }).filter(Boolean);
      var yes = doms.filter(function (d) { return d[0] !== "~"; });
      var no = doms.filter(function (d) { return d[0] === "~"; }).map(function (d) { return d.slice(1); });
      if (yes.length && !yes.some(on)) return;
      if (no.some(on)) return;
      css += sel + "{display:none!important}\n";
    });
    return css;
  }

  // What a login wall or an app prompt links to, in any language
  var WALL_LINK = /\/(accounts\/)?(login|signup|emailsignup|reg)\b|\/r\.php|apps\.apple\.com|itunes\.apple\.com|play\.google\.com|onelink\.me|app\.link|app\.adjust|intent:|instagram:|fb:|snssdk|market:/i;
  // ...and what it says (English and Portuguese; the link check above does most of the work)
  var WALL_TEXT = /\b(log ?in|sign ?up|create (an |new )?account|open (the |in )?app|get (the )?app|use (the )?app|continue (in|with)|see more (from|on)|iniciar sess[aã]o|entrar|inscrever|registar|criar conta|abrir (a |na )?(app|aplica)|obter a app)\b/i;

  var style = document.createElement("style");
  style.id = "lurkr-cleanup";
  style.textContent = filterCss() +
    "\nhtml,body{overflow:auto!important;overscroll-behavior:auto!important}" +
    "\n[data-lurkr-hidden]{display:none!important}";

  function addStyle() {
    var root = document.head || document.documentElement;
    if (root && !style.isConnected) root.appendChild(style);
  }

  // A modal the page opened keeps its scroll lock after Lurkr hides it: libraries like react-remove-scroll cancel
  // every touchmove and wheel outside the (now hidden) modal, so the page won't scroll. Once Lurkr has hidden a
  // modal, those cancels are ignored, unless the gesture is inside a modal still showing. Patched here, at
  // document start, before the page's own scripts.
  var modalHidden = false;
  var preventDefault = Event.prototype.preventDefault;
  Event.prototype.preventDefault = function () {
    if (modalHidden && (this.type === "touchmove" || this.type === "wheel")) {
      var t = this.target;
      var open = t && t.closest && t.closest('[role="dialog"]:not([data-lurkr-hidden]), [aria-modal="true"]:not([data-lurkr-hidden])');
      if (!open || (open.closest && open.closest("[data-lurkr-hidden]"))) return;
    }
    return preventDefault.call(this);
  };

  function hide(el, why) {
    if (!el || el === document.body || el === document.documentElement || el.hasAttribute("data-lurkr-hidden")) return;
    if (why === "dialog") modalHidden = true;
    var bar = el.getBoundingClientRect();
    el.setAttribute("data-lurkr-hidden", why);
    // A bar fixed to the top leaves the space the page kept free for it (a padding as tall as the bar): close it
    if (bar.top <= 4 && bar.height > 20 && bar.height < 160 && getComputedStyle(el).position === "fixed") closeGap(bar.height);
    if (window.__lurkrDebug) console.log("[Lurkr] hid (" + why + ")", el);
  }

  function closeGap(h) {
    document.elementsFromPoint(innerWidth / 2, h + 4).forEach(function (n) {
      if (n === document.documentElement || n.hasAttribute("data-lurkr-hidden")) return;
      var pt = parseFloat(getComputedStyle(n).paddingTop) || 0;
      if (Math.abs(pt - h) <= 12) n.style.setProperty("padding-top", "0", "important");
    });
  }

  function hasContent(el) {
    // Never hide the post itself: anything with a video, an article or a big picture in it
    if (el.querySelector("video, article")) return true;
    var imgs = el.querySelectorAll("img");
    for (var i = 0; i < imgs.length; i++) {
      var r = imgs[i].getBoundingClientRect();
      if (r.width >= 200 && r.height >= 200) return true;
    }
    return false;
  }

  function isWall(el) {
    var links = el.querySelectorAll("a[href], form[action]");
    for (var i = 0; i < links.length; i++) {
      if (WALL_LINK.test(links[i].getAttribute("href") || links[i].getAttribute("action") || "")) return true;
    }
    var text = (el.innerText || "").slice(0, 600);
    return text.length < 600 && WALL_TEXT.test(text);
  }

  var COOKIE_TEXT = /\b(cookies?|consent|privacy (settings|choices)|consentimento)\b/i;

  function isCookieBar(el) {
    var text = (el.innerText || "").trim();
    return text.length > 0 && text.length < 700 && COOKIE_TEXT.test(text) && !!el.querySelector('button, [role="button"], a');
  }

  // The overlay layer a dialog sits in: its outermost fixed/sticky ancestor that is mostly the dialog (not a
  // fixed page wrapper like Instagram's #scrollview, which holds the whole page), or null
  function overlayOf(el) {
    var found = null;
    var own = (el.innerText || "").length;
    for (var n = el.parentElement; n && n !== document.body; n = n.parentElement) {
      if ((n.innerText || "").length > own * 1.5 + 200 || hasContent(n)) break;
      var p = getComputedStyle(n).position;
      if (p === "fixed" || p === "sticky") found = n;
    }
    return found;
  }

  function covers(el, share) {
    var r = el.getBoundingClientRect();
    return r.width * r.height >= share * innerWidth * innerHeight;
  }

  function unlockScroll() {
    [document.documentElement, document.body].forEach(function (el) {
      if (!el) return;
      var cs = getComputedStyle(el);
      if (cs.overflow === "hidden" || cs.overflowY === "hidden") el.style.setProperty("overflow", "auto", "important");
      if (el === document.body && cs.position === "fixed") {
        el.style.setProperty("position", "static", "important");
        el.style.removeProperty("top");
      }
    });
  }

  // Cookie consent: wiped storage means it asks on every visit. Pick the least: decline the optional cookies.
  var EXTRA_DECLINE = (PAGE.decline || []).map(function (t) { return t.trim().toLowerCase(); });
  var DECLINE = /^\s*(decline optional cookies|only allow essential cookies|reject optional cookies|(refuse|reject|decline) non-essential cookies|recusar cookies n[aã]o essenciais|reject all( cookies)?|decline all|decline|recusar cookies opcionais|permitir apenas (os )?cookies essenciais|rejeitar (todos|tudo|cookies opcionais)|recusar|i do not agree|i don't agree|do not agree|disagree|n[aã]o concordo|continue without (agreeing|accepting))\s*$/i;

  function declineCookies(root) {
    var buttons = root.querySelectorAll('button, [role="button"]');
    for (var i = 0; i < buttons.length; i++) {
      var b = buttons[i];
      var label = (b.innerText || b.textContent || "").trim();
      if (b.hasAttribute("data-lurkr-clicked") || !(DECLINE.test(label) || EXTRA_DECLINE.indexOf(label.toLowerCase()) >= 0)) continue;
      var r = b.getBoundingClientRect();
      if (!r.width || !r.height) continue;
      b.setAttribute("data-lurkr-clicked", "");
      if (window.__lurkrDebug) console.log("[Lurkr] declined cookies", b);
      b.click();
    }
  }

  var hidDialog = false;

  function sweep() {
    addStyle();
    if (!document.body) return;
    unlockScroll();
    declineCookies(document);
    // Cookie banners inside custom elements' shadow roots (TikTok's <tiktok-cookie-banner>)
    var hosts = document.querySelectorAll("[class*=cookie], [id*=cookie], tiktok-cookie-banner");
    for (var h = 0; h < hosts.length; h++) if (hosts[h].shadowRoot) declineCookies(hosts[h].shadowRoot);

    // 1. Dialogs and modals that want a login or the app: hide them with their overlay layer
    var dialogs = document.querySelectorAll('[role="dialog"], [aria-modal="true"], dialog[open]');
    for (var i = 0; i < dialogs.length; i++) {
      var d = dialogs[i];
      // A modal a filters.txt rule hides from the start still locked the page's scrolling
      if (d.getAttribute("aria-modal") === "true" && getComputedStyle(d).display === "none") modalHidden = true;
      if (d.closest("[data-lurkr-hidden]") || hasContent(d) || !isWall(d)) continue;
      hide(overlayOf(d) || d, "dialog");
      hidDialog = true;
    }

    // 2. Fixed banners and bars ("Open app", "Log in to see more"): smaller fixed layers with a wall link or text
    var all = document.body.querySelectorAll("div, section, aside, footer, header, nav");
    for (var j = 0; j < all.length && j < 4000; j++) {
      var el = all[j];
      var pos = getComputedStyle(el).position;
      if (pos !== "fixed" && pos !== "sticky") continue;
      if (el.closest("[data-lurkr-hidden]")) continue;
      if (hasContent(el)) continue;
      var big = covers(el, 0.4);
      if (!big && isWall(el)) { hide(el, "banner"); continue; }
      // Cookie bars with nothing to decline ("Yes, I agree" / "Settings"): just hidden. Lurkr wipes cookies after
      // every visit anyway, so nothing is agreed to.
      if (!big && isCookieBar(el)) { hide(el, "cookie bar"); continue; }
      // 3. Once a dialog is hidden: the empty dim layer it leaves behind (full-screen, no text, coloured)
      if (hidDialog && covers(el, 0.8) && !(el.innerText || "").trim() && !el.querySelector("img, video, canvas, iframe")) {
        var bg = getComputedStyle(el).backgroundColor;
        if (bg && bg !== "transparent" && bg !== "rgba(0, 0, 0, 0)") hide(el, "backdrop");
      }
    }
    // 4. Sign-up and log-in buttons anywhere on the page ("Sign up for Instagram"): a lurker never needs them.
    //    Their wrapper goes too when it holds nothing else, so no empty gap is left.
    var buttons = document.querySelectorAll('a[href*="signup"], a[href*="/login"], a[href*="/r.php"], a[href*="/reg/"]');
    for (var k = 0; k < buttons.length; k++) {
      var b = buttons[k];
      if (b.closest("[data-lurkr-hidden]") || hasContent(b)) continue;
      var text = (b.innerText || "").trim();
      if (text.length > 60) continue;
      var wrap = b.parentElement;
      hide(wrap && wrap !== document.body && (wrap.innerText || "").trim() === text ? wrap : b, "sign-up button");
    }
    pageSettings();
    noLogin();
    if (window.__lurkrSite) window.__lurkrSite();
  }

  // Videos never play by themselves: a video starts when it's tapped, with sound. Until then any play (autoplay,
  // the page's own scripts) is paused at once. "play" doesn't bubble, so it's caught on its way down.
  // A play during a tap (the browser says so: navigator.userActivation, which also covers taps on the browser's
  // own video controls, which the page never sees) counts as the tap, and gets sound.
  // Opening a link counts as a "tap" for a few seconds in a WebView, so the browser's word alone only counts
  // once the page has been open a while, or right after a touch Lurkr saw.
  var lastTouch = 0;
  function tapNow(v) {
    if (v.__lurkrTapped) return true;
    // Just paused by a tap: that same tap doesn't let the page start it again
    if (v.__lurkrHold && Date.now() - v.__lurkrHold < 2000) return false;
    if (!(navigator.userActivation && navigator.userActivation.isActive)) return false;
    if (Date.now() - lastTouch > 1500 && performance.now() < 6000) return false;
    v.__lurkrTapped = true;
    v.muted = false;
    return true;
  }
  // Until the tap, a video may start once, muted, just long enough to show its first frame (players like
  // Instagram's only load a picture by playing): it's paused as soon as it plays. After that, the page's own
  // play() calls do nothing.
  var mediaPlay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () {
    if (this.tagName === "VIDEO" && !tapNow(this)) {
      if (this.__lurkrPreview) return Promise.resolve();
      this.muted = true;
    }
    return mediaPlay.apply(this, arguments);
  };
  document.addEventListener("play", function (e) {
    var v = e.target;
    if (v.tagName === "VIDEO" && !tapNow(v) && v.__lurkrPreview) v.pause();
  }, true);
  document.addEventListener("playing", function (e) {
    var v = e.target;
    if (v.tagName !== "VIDEO" || tapNow(v)) return;
    v.__lurkrPreview = true;
    v.pause();
  }, true);
  // No WebView placeholder (a big grey play icon) on a video without a picture yet: a see-through poster
  var CLEAR = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7";
  document.addEventListener("loadstart", function (e) {
    var v = e.target;
    if (v.tagName === "VIDEO" && !v.getAttribute("poster")) v.setAttribute("poster", CLEAR);
  }, true);
  // The tapped video: the one tapped, or the only one in the few layers around what was tapped (players put
  // their controls and overlays on top of the video)
  function tappedVideo(el, x, y) {
    // Not Lurkr's own buttons (they handle their taps), nor a button or a link of the player's (like, share …)
    if (el && el.closest && el.closest("[data-lurkr-button]")) return null;
    var control = el && el.closest && el.closest("a, button, [role=button], input, textarea, select");
    if (control && !control.querySelector("video")) return null;
    // Up to 15 layers: Facebook's overlays sit that far above its video
    for (var i = 0; el && el !== document.body && i < 15; i++, el = el.parentElement) {
      if (el.tagName === "VIDEO") return el;
      var vs = el.querySelectorAll ? el.querySelectorAll("video") : [];
      if (vs.length === 1) return vs[0];
      // Several (Facebook stacks a few): the one under the tap that has something loaded, playing ones first
      if (vs.length > 1) {
        var hit = [].filter.call(vs, function (v) {
          var r = v.getBoundingClientRect();
          return v.currentSrc && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;
        });
        return hit.filter(function (v) { return !v.paused; })[0] || hit[0] || null;
      }
    }
    return null;
  }
  // A tap on a video plays it (with sound) or pauses it, the same everywhere: the player's own idea of a tap
  // (Instagram's mutes, for one) doesn't get it. Videos with the browser's own controls keep them for pausing;
  // a tap only starts those.
  // The player never hears about taps on the picture (no mute icons, no pausing of its own): only Lurkr does.
  // The browser still makes them a click.
  ["pointerdown", "pointerup", "touchstart", "touchend", "mousedown", "mouseup"].forEach(function (type) {
    // On window, before anything of the page's: neither taps on the picture nor on Lurkr's buttons reach it
    window.addEventListener(type, function (e) {
      lastTouch = Date.now();
      var mine = e.target.closest && e.target.closest("[data-lurkr-button]");
      var p = e.touches && e.touches[0] || e.changedTouches && e.changedTouches[0] || e;
      var v = tappedVideo(e.target, p.clientX, p.clientY);
      if (mine || (v && !v.controls)) e.stopPropagation();
    }, true);
  });
  document.addEventListener("click", function (e) {
    var v = tappedVideo(e.target, e.clientX, e.clientY);
    if (!v) return;
    v.__lurkrTapped = true;
    v.__lurkrHold = 0;
    v.muted = false;
    v.loop = false;
    v.__lurkrStarted = true;
    if (v.__lurkrEnded) { v.__lurkrEnded = false; v.currentTime = 0; }
    if (v.paused) {
      var p = v.play();
      if (p && p.catch) p.catch(function () {});
    } else if (!v.controls) {
      // Paused by a tap: the page can't start it again (Facebook's player tries at once), only the next tap can
      v.__lurkrTapped = false;
      v.__lurkrPreview = true;
      v.__lurkrHold = Date.now();
      v.pause();
    } else {
      return;
    }
    if (!v.controls) { e.stopPropagation(); e.preventDefault(); }
  }, true);
  // Videos play once: no looping (players like Facebook's keep switching it back on, so it's switched off as they
  // play). At the end the video waits for a tap again, so a player that restarts it by itself (TikTok's) can't,
  // and the button shows restart.
  // The property can't be switched on at all: YouTube switches it back on many times a second, faster than the events
  // below catch it, so its Shorts went round again instead of ending
  var loop = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "loop");
  Object.defineProperty(HTMLMediaElement.prototype, "loop", {
    configurable: true, enumerable: loop.enumerable,
    get: function () { return false; },
    set: function () { loop.set.call(this, false); },
  });
  ["loadedmetadata", "play", "timeupdate"].forEach(function (type) {
    document.addEventListener(type, function (e) { if (e.target.tagName === "VIDEO" && e.target.hasAttribute("loop")) e.target.removeAttribute("loop"); }, true);
  });
  document.addEventListener("ended", function (e) {
    var v = e.target;
    if (v.tagName !== "VIDEO") return;
    v.__lurkrTapped = false;
    v.__lurkrPreview = true;
    v.__lurkrEnded = true;
  }, true);
  // At the end it stays on its last frame: A player that jumps back to the start by itself (Facebook's) can't.
  // Lurkr's own restart clears the ended mark first.
  var seek = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, "currentTime");
  Object.defineProperty(HTMLMediaElement.prototype, "currentTime", {
    configurable: true, enumerable: seek.enumerable, get: seek.get,
    set: function (t) { if (!this.__lurkrEnded) seek.set.call(this, t); },
  });
  document.addEventListener("playing", function (e) { if (e.target.tagName === "VIDEO") e.target.__lurkrEnded = false; }, true);

  // Lurkr's own buttons over every video without the browser's controls. Paused: rewind and play, side by side
  // (just play before it has ever played). Ended: just rewind. Playing: none. A tap elsewhere on the video plays
  // or pauses it (the click handler above).
  var PLAY = "M9 7v18l15-9z";
  var REWIND = "M16 7a9 9 0 1 1-8.5 6h3.2A6 6 0 1 0 16 10v3.5L11 8.5 16 3.5z";
  // Built element by element: Pages like YouTube's refuse HTML strings (Trusted Types)
  function icon(d, size) {
    var NS = "http://www.w3.org/2000/svg", svg = document.createElementNS(NS, "svg"), path = document.createElementNS(NS, "path");
    svg.setAttribute("width", size);
    svg.setAttribute("height", size);
    svg.setAttribute("viewBox", "0 0 32 32");
    path.setAttribute("d", d);
    path.setAttribute("fill", "#fff");
    svg.appendChild(path);
    return svg;
  }
  // They go in the box the video is positioned against, at the video's centre: the page's layout is left alone
  // (making the video's own parent "relative" collapses players like TikTok's). The same on every site: big
  // white icons on dark see-through circles.
  function look() {
    return { size: 80, icon: 44, background: "rgba(0,0,0,.65)" };
  }
  var GAP = 24, FADE = 0.25;
  function buttons(v) {
    var c = v.__lurkrButtons, L = look();
    var box = v.offsetParent || v.parentElement;
    if (!box) return null;
    if (!c || !c.isConnected || c.parentElement !== box) {
      if (c) c.remove();
      c = document.createElement("div");
      c.setAttribute("data-lurkr-button", "");
      c.style.cssText = "position:absolute;display:flex;gap:" + GAP + "px;pointer-events:none;z-index:2147483647;" +
        "opacity:0;transition:opacity " + FADE + "s ease";
      ["rewind", "play"].forEach(function (action) {
        var b = document.createElement("div");
        b.setAttribute("data-lurkr-action", action);
        b.style.cssText = "width:" + L.size + "px;height:" + L.size + "px;border-radius:50%;background:" + L.background +
          ";display:flex;align-items:center;justify-content:center;pointer-events:auto";
        b.appendChild(icon(action === "play" ? PLAY : REWIND, L.icon));
        c.appendChild(b);
      });
      c.__video = v;
      box.appendChild(c);
      v.__lurkrButtons = c;
    }
    return c;
  }
  function showButton(v) {
    if (v.controls) { if (v.__lurkrButtons) v.__lurkrButtons.remove(); return; }
    var c = buttons(v);
    if (!c) return;
    var r = v.getBoundingClientRect(), L = look();
    var ended = v.ended || v.__lurkrEnded;
    // Nothing on a video too small to see (players' hidden or background ones), or while it plays
    // They fade in and out (and can't be tapped while faded), staying where they were
    var shown = r.width >= 80 && r.height >= 80 && v.paused;
    c.style.opacity = shown ? "1" : "0";
    for (var i = 0; i < c.children.length; i++) c.children[i].style.pointerEvents = shown ? "auto" : "none";
    if (!shown) return;
    var rewind = ended || v.__lurkrStarted, play = !ended;
    c.children[0].style.display = rewind ? "flex" : "none";
    c.children[1].style.display = play ? "flex" : "none";
    var w = (rewind && play ? 2 * L.size + GAP : L.size);
    var box = c.parentElement, br = box.getBoundingClientRect();
    c.style.left = (r.left - br.left + box.scrollLeft + r.width / 2 - w / 2) + "px";
    c.style.top = (r.top - br.top + box.scrollTop + r.height / 2 - L.size / 2) + "px";
  }
  // A tap on one of them: rewind (from the start) or play, with sound
  document.addEventListener("click", function (e) {
    var b = e.target.closest && e.target.closest("[data-lurkr-action]");
    if (!b) return;
    e.stopPropagation();
    e.preventDefault();
    var v = b.parentElement.__video;
    if (!v) return;
    v.__lurkrTapped = true;
    v.__lurkrStarted = true;
    v.__lurkrHold = 0;
    v.muted = false;
    v.loop = false;
    if (b.getAttribute("data-lurkr-action") === "rewind" || v.__lurkrEnded) { v.__lurkrEnded = false; v.currentTime = 0; }
    var p = v.play();
    if (p && p.catch) p.catch(function () {});
  }, true);
  // While a video plays, only the video: everything else on the page (author, caption, like and share buttons,
  // the site's controls, the rest of the feed) fades out, and comes back when it's paused or ends. The video and the
  // boxes it sits in are marked, and a style rule fades everything else, so it holds when the site re-renders its
  // layers (Facebook does, as the video starts).
  // Fullscreen shows the whole picture (YouTube crops its videos to fill the player, which cut them off at the sides)
  style.textContent += "\nvideo:fullscreen, :fullscreen video{object-fit:contain!important}";
  style.textContent += "\nhtml[data-lurkr-solo] body *:not([data-lurkr-keep]):not(video):not([data-lurkr-button]):not([data-lurkr-button] *)" +
    "{opacity:0!important;pointer-events:none!important;transition:opacity " + FADE + "s ease!important}" +
    // Fading back in: the same transition for a moment after, or everything would snap back
    "\nhtml[data-lurkr-unsolo] body *{transition:opacity " + FADE + "s ease!important}";
  var unsolo = 0;
  function fade(v, out) {
    if (v.controls) return;
    var html = document.documentElement;
    if (!out && html.hasAttribute("data-lurkr-solo")) {
      html.setAttribute("data-lurkr-unsolo", "");
      clearTimeout(unsolo);
      unsolo = setTimeout(function () { html.removeAttribute("data-lurkr-unsolo"); }, FADE * 1000 + 100);
    }
    document.querySelectorAll("[data-lurkr-keep]").forEach(function (n) { n.removeAttribute("data-lurkr-keep"); });
    html.removeAttribute("data-lurkr-solo");
    if (!out) return;
    var r = v.getBoundingClientRect();
    if (r.width < 200 || r.height < 200) return;
    for (var n = v.parentElement; n && n !== document.body; n = n.parentElement) n.setAttribute("data-lurkr-keep", "");
    document.documentElement.setAttribute("data-lurkr-solo", "");
  }
  document.addEventListener("playing", function (e) { if (e.target.tagName === "VIDEO" && e.target.__lurkrTapped) fade(e.target, true); }, true);
  ["pause", "ended", "emptied"].forEach(function (type) {
    document.addEventListener(type, function (e) { if (e.target.tagName === "VIDEO" && e.target.paused) fade(e.target, false); }, true);
  });
  addEventListener("resize", function () { document.querySelectorAll("video").forEach(showButton); });
  ["loadeddata", "play", "playing", "pause", "ended", "seeked"].forEach(function (type) {
    document.addEventListener(type, function (e) { if (e.target.tagName === "VIDEO") showButton(e.target); }, true);
  });

  // Lurkr's progress bar along the bottom of the video while it plays, the same on every site (the sites' own are
  // hidden), and the one thing besides the video still there. A tap jumps there, a drag scrubs. Paused, it goes
  // and the site's things and Lurkr's buttons come back.
  function bar(v) {
    var g = v.__lurkrBar, box = v.offsetParent || v.parentElement;
    if (!box) return null;
    if (!g || !g.isConnected || g.parentElement !== box) {
      if (g) g.remove();
      g = document.createElement("div");
      g.setAttribute("data-lurkr-button", "");
      g.setAttribute("data-lurkr-bar", "");
      g.style.cssText = "position:absolute;height:32px;z-index:2147483647;touch-action:none;pointer-events:none;" +
        "opacity:0;transition:opacity " + FADE + "s ease";
      // Drawn as an SVG: Facebook's page is darkened by the browser (Lurkr's dark mode), which turns plain white
      // boxes dark but leaves SVG drawings as they are (like the buttons' icons)
      var NS = "http://www.w3.org/2000/svg", track = document.createElementNS(NS, "svg");
      track.style.cssText = "position:absolute;left:16px;right:16px;bottom:6px;height:16px;width:calc(100% - 32px);overflow:visible";
      [["rect", "rgba(255,255,255,.35)", "100%"], ["rect", "#fff", "0"], ["circle", "#fff"]].forEach(function (d) {
        var el = document.createElementNS(NS, d[0]);
        el.setAttribute("fill", d[1]);
        if (d[0] === "rect") {
          el.setAttribute("y", "6.5"); el.setAttribute("height", "3"); el.setAttribute("rx", "1.5"); el.setAttribute("width", d[2]);
        } else {
          el.setAttribute("cy", "8"); el.setAttribute("r", "6"); el.setAttribute("cx", "0");
        }
        track.appendChild(el);
      });
      g.appendChild(track);
      g.__video = v;
      box.appendChild(g);
      v.__lurkrBar = g;
    }
    return g;
  }
  function showBar(v) {
    if (v.controls) { if (v.__lurkrBar) v.__lurkrBar.remove(); return; }
    var g = bar(v);
    if (!g) return;
    var r = v.getBoundingClientRect();
    var shown = r.width >= 200 && r.height >= 200 && v.__lurkrStarted && isFinite(v.duration) && v.duration > 0;
    g.style.opacity = shown ? "1" : "0";
    g.style.pointerEvents = shown ? "auto" : "none";
    if (!shown) return;
    var box = g.parentElement, br = box.getBoundingClientRect();
    var bottom = Math.min(r.bottom, innerHeight);
    g.style.left = (r.left - br.left + box.scrollLeft) + "px";
    g.style.width = r.width + "px";
    g.style.top = (bottom - br.top + box.scrollTop - 32) + "px";
    progress(v);
  }
  function progress(v) {
    var g = v.__lurkrBar;
    if (!g || !v.duration) return;
    var f = Math.max(0, Math.min(1, v.currentTime / v.duration)) * 100 + "%";
    var track = g.firstChild;
    track.children[1].setAttribute("width", f);
    track.children[2].setAttribute("cx", f);
  }
  // Smooth while playing (timeupdate only comes a few times a second)
  function tick(v) {
    progress(v);
    if (!v.paused) requestAnimationFrame(function () { tick(v); });
  }
  ["loadedmetadata", "durationchange", "play", "pause", "ended", "seeked"].forEach(function (type) {
    document.addEventListener(type, function (e) { if (e.target.tagName === "VIDEO") showBar(e.target); }, true);
  });
  document.addEventListener("playing", function (e) { if (e.target.tagName === "VIDEO") { showBar(e.target); tick(e.target); } }, true);
  addEventListener("resize", function () { document.querySelectorAll("video").forEach(showBar); });
  // Scrubbing. On window, alongside the listener that keeps taps on Lurkr's controls from the page
  var scrub = null;
  function seekTo(g, x) {
    var v = g.__video, t = g.firstChild.getBoundingClientRect();
    if (!v.duration) return;
    var f = Math.max(0, Math.min(1, (x - t.left) / t.width));
    v.currentTime = f * v.duration;
    progress(v);
  }
  window.addEventListener("pointerdown", function (e) {
    var g = e.target.closest && e.target.closest("[data-lurkr-bar]");
    if (!g) return;
    scrub = g;
    seekTo(g, e.clientX);
  }, true);
  window.addEventListener("pointermove", function (e) {
    if (!scrub) return;
    e.stopPropagation();
    seekTo(scrub, e.clientX);
  }, true);
  ["pointerup", "pointercancel"].forEach(function (type) {
    window.addEventListener(type, function () { scrub = null; }, true);
  });

  // Logged out by design: no logging in. Forms with a password field are hidden and can't be sent.
  document.addEventListener("submit", function (e) {
    if (e.target.querySelector && e.target.querySelector("input[type=password]")) { e.preventDefault(); e.stopPropagation(); }
  }, true);
  function noLogin() {
    document.querySelectorAll("input[type=password]").forEach(function (p) {
      hide(p.closest("form") || p, "login form");
    });
  }

  // site.json "page": things to click once per page, and text-marked layers to hide
  var clicked = {};
  var HIDE_TEXT = (PAGE.hideText || []).map(function (r) { return new RegExp(r, "i"); });

  function pageSettings() {
    (PAGE.click || []).forEach(function (sel) {
      if (clicked[location.href + " " + sel]) return;
      var el = document.querySelector(sel);
      if (!el || !el.getBoundingClientRect().width) return;
      clicked[location.href + " " + sel] = true;
      if (window.__lurkrDebug) console.log("[Lurkr] clicked", sel);
      el.click();
    });
    if (HIDE_TEXT.length) {
      var els = document.querySelectorAll("h1, h2, h3, h4, span, div, p");
      for (var j = 0; j < els.length; j++) {
        var t = els[j].textContent || "";
        if (els[j].childElementCount > 2 || t.length > 80 || !HIDE_TEXT.some(function (r) { return r.test(t); })) continue;
        // The overlay layer around it (its nearest positioned ancestor), if it's mostly that message
        for (var n = els[j].parentElement; n && n !== document.body; n = n.parentElement) {
          var pos = getComputedStyle(n).position;
          if (pos === "absolute" || pos === "fixed") {
            if ((n.innerText || "").length < 200) hide(n, "text: " + t.trim().slice(0, 30));
            break;
          }
        }
      }
    }
  }

  var queued = false;
  function schedule() {
    if (queued) return;
    queued = true;
    setTimeout(function () { queued = false; try { sweep(); } catch (e) {} }, 250);
  }

  new MutationObserver(schedule).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ["style", "class", "open"] });
  document.addEventListener("DOMContentLoaded", schedule);
  window.addEventListener("load", schedule);
  window.addEventListener("scroll", schedule, { passive: true });
  addStyle();

  // site.js (sites/<id>/site.js): anything the above can't do. It runs here, once per page, and can set
  // window.__lurkrSite to run after each sweep.
  /*__SITE_JS__*/
})();

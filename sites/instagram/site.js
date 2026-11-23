  // Instagram, logged out: profile grid tiles link to the app (intent://…, falling back to the Play Store)
  // instead of the post. Open the post here instead: React's props for the tile hold its media id, and the
  // post's code in /p/<code>/ is that number in base 64.
  (function () {
    var ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    function shortcode(pk) {
      var n = BigInt(pk), c = "";
      while (n > BigInt(0)) { c = ALPHABET[Number(n % BigInt(64))] + c; n = n / BigInt(64); }
      return c;
    }

    function mediaOf(el) {
      var key = Object.keys(el).find(function (k) { return k.indexOf("__reactFiber") === 0; });
      for (var f = key && el[key], d = 0; f && d < 15; f = f.return, d++) {
        var p = f.memoizedProps;
        var m = p && typeof p === "object" && (p.media || p.post || p.item);
        if (m && (m.code || m.pk)) return m;
      }
      return null;
    }

    document.addEventListener("click", function (e) {
      var a = e.target && e.target.closest && e.target.closest('a[href^="intent:"]');
      if (!a) return;
      var m = mediaOf(a);
      var pk = m && String(m.pk || m.id || "").split("_")[0];
      var code = m && (m.code || (/^\d+$/.test(pk) ? shortcode(pk) : null));
      if (!code) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      location.href = "/p/" + code + "/";
    }, true);
  })();


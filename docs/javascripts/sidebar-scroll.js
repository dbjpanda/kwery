/*
 * Keep the sidebar still when you click something in it.
 *
 * navigation.instant swaps the page without a reload, and in doing so it
 * replaces the sidebar's DOM node outright rather than reusing it. Material
 * then scrolls the fresh node so the newly active item is visible. The result
 * is that clicking a link you can already see makes the list jump under the
 * cursor: measured going from scrollTop 300 to 175 on a single click.
 *
 * That jump was always happening; it only became visible once the sidebar was
 * given its own scrollbar, because before that there was no scroll offset to
 * lose.
 *
 * So: remember the offset at click time, and put it back after the swap. The
 * restore runs inside requestAnimationFrame because Material sets its own
 * scroll position during the same turn, and the last write wins.
 */
(function () {
  var WRAP = ".md-sidebar--primary .md-sidebar__scrollwrap";
  var saved = null;

  // Capture on the way down, before anything can replace the node.
  document.addEventListener(
    "click",
    function (event) {
      var target = event.target;
      if (!target || !target.closest) return;
      if (!target.closest(".md-sidebar--primary a.md-nav__link")) return;
      var wrap = document.querySelector(WRAP);
      if (wrap) saved = wrap.scrollTop;
    },
    true
  );

  // document$ is Material's per-page-swap observable, and is the supported
  // place to run code that must survive instant navigation.
  if (typeof document$ !== "undefined" && document$ && document$.subscribe) {
    document$.subscribe(function () {
      if (saved === null) return;
      var offset = saved;
      requestAnimationFrame(function () {
        var wrap = document.querySelector(WRAP);
        if (wrap) wrap.scrollTop = offset;
      });
    });
  }
})();

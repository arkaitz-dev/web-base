// web-base: after wb/link swaps the page's <main>, move the focus to it, as loading a
// page would, so a screen reader announces the new page and Tab starts from its top.
// An [autofocus] in the new content is focused by htmx after this, and wins. A file and
// not hx-on, which a strict CSP forbids. htmx 4 dispatches on document when the link
// that started the swap is gone with the old <main>.
//
// It also marks the chrome's current page. The header and the nav sit outside <main> and
// are not swapped, so a mark drawn for the first page would stay on it: on load and after
// every swap, the link of a .wb-nav or of a [data-wb-nav] outside <main> whose path is the
// address bar's is aria-current=page, and no other is: page means this very page, and in
// the chrome it is this script's. A section a page belongs to is the host's to mark, as
// aria-current="true" or "location", which is left as it is ("false" counts as no mark).
// A link to a fragment or to a query of the same page ("#…", "?…", "") is no page of its own.
function markCurrent() {
  document.querySelectorAll(".wb-nav a[href], [data-wb-nav] a[href]").forEach(function (a) {
    var href = a.getAttribute("href");
    if (a.closest("main") || !href || href[0] === "#" || href[0] === "?") return;
    var here = a.origin === location.origin && a.pathname === location.pathname;
    var mark = a.getAttribute("aria-current");
    if (here && (!mark || mark === "false")) a.setAttribute("aria-current", "page");
    if (!here && mark === "page") a.removeAttribute("aria-current");
  });
}

document.addEventListener("htmx:after:swap", function (event) {
  markCurrent();
  var ctx = event.detail && event.detail.ctx;
  if (!ctx || ctx.select !== "main") return;
  var main = document.querySelector("main.wb-main");
  if (main) main.focus({ preventScroll: true });
});

markCurrent();

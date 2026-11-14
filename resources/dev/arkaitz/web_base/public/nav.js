// web-base: after wb/link swaps the page's <main>, move the focus to it, as loading a
// page would, so a screen reader announces the new page and Tab starts from its top.
// An [autofocus] in the new content is focused by htmx after this, and wins. A file and
// not hx-on, which a strict CSP forbids. htmx 4 dispatches on document when the link
// that started the swap is gone with the old <main>.
document.addEventListener("htmx:after:swap", function (event) {
  var ctx = event.detail && event.detail.ctx;
  if (!ctx || ctx.select !== "main") return;
  var main = document.querySelector("main.wb-main");
  if (main) main.focus({ preventScroll: true });
});

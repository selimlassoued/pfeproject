/**
 * HireAI Keycloak theme sync.
 *
 * Reads the `ui_theme` URL parameter passed by the Angular app (which
 * calls keycloak.login({ ... }) with the user's currently-selected
 * theme appended). Falls back to OS preference if absent. Writes the
 * resolved value to <html data-theme="..."> so the CSS at
 * :root[data-theme="light"] / [data-theme="dark"] takes effect.
 *
 * Runs in <head> via theme.properties `scripts=` so the brief CSS
 * flash is acceptable because the script executes before <body>
 * paints in most browsers.
 */
(function () {
  /**
   * Order of precedence:
   *   1. ?ui_theme=light|dark in the URL  (set by Angular when bouncing to Keycloak)
   *   2. sessionStorage  (so Register / Forgot Password / TOTP etc. keep the
   *      theme the user landed with, even though Keycloak builds those links
   *      server-side without the query param)
   *   3. dark  (default for direct visits and after closing the tab)
   *
   * Also injects a floating sun/moon toggle on every page so the user can
   * flip the theme directly from the Keycloak UI. The choice is persisted
   * to sessionStorage so it carries through Register, Forgot Password, etc.
   */
  var STORAGE_KEY = "hireai-ui-theme";

  function resolveInitialTheme() {
    try {
      var params = new URLSearchParams(window.location.search);
      var fromUrl = params.get("ui_theme");
      if (fromUrl === "light" || fromUrl === "dark") {
        try { sessionStorage.setItem(STORAGE_KEY, fromUrl); } catch (e) {}
        return fromUrl;
      }
      var stored = null;
      try { stored = sessionStorage.getItem(STORAGE_KEY); } catch (e) {}
      return stored === "light" ? "light" : "dark";
    } catch (e) {
      return "dark";
    }
  }

  function applyTheme(theme) {
    document.documentElement.setAttribute("data-theme", theme);
    try { sessionStorage.setItem(STORAGE_KEY, theme); } catch (e) {}
  }

  applyTheme(resolveInitialTheme());

  function svgSun() {
    return '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<circle cx="12" cy="12" r="4"/>' +
      '<path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/>' +
      '</svg>';
  }

  function svgMoon() {
    return '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>' +
      '</svg>';
  }

  function refreshIcon(btn) {
    var current = document.documentElement.getAttribute("data-theme") || "dark";
    btn.innerHTML = current === "light" ? svgMoon() : svgSun();
    btn.setAttribute(
      "aria-label",
      current === "light" ? "Switch to dark mode" : "Switch to light mode"
    );
    btn.setAttribute(
      "title",
      current === "light" ? "Switch to dark mode" : "Switch to light mode"
    );
  }

  function injectToggle() {
    if (document.getElementById("hireai-theme-toggle")) return;
    var btn = document.createElement("button");
    btn.id = "hireai-theme-toggle";
    btn.type = "button";
    btn.className = "hireai-theme-toggle";
    refreshIcon(btn);
    btn.addEventListener("click", function () {
      var next = document.documentElement.getAttribute("data-theme") === "light" ? "dark" : "light";
      applyTheme(next);
      refreshIcon(btn);
    });
    document.body.appendChild(btn);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", injectToggle);
  } else {
    injectToggle();
  }
})();

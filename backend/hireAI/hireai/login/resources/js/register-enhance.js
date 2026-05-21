/*
 * HireAI login-theme enhancements (injected on every login-theme page via the
 * `scripts=` directive in theme.properties).
 *
 * Phone number:
 *   The realm validates `phoneNumber` against ^\+216\d{8}$. The user types only
 *   the 8 local digits; a fixed "+216" prefix is shown beside the field and
 *   concatenated onto the value at submit time so the server accepts it.
 *
 * Errors:
 *   Every field-level and global validation error is collected into a single
 *   red banner pinned to the top of the form, so the user cannot miss it.
 */
(function () {
  "use strict";

  // NOTE: the "+216" phone prefix is handled per-page by the inline script in
  // each FTL (register / login-update-profile / idp-review-user-profile).
  // It is intentionally NOT done here — running both produced a duplicated
  // "+216" box on the profile pages.

  // ── Errors: one consolidated red banner at the top of the form ────────
  function collectErrors() {
    var seen = {};
    var out = [];
    var nodes = document.querySelectorAll(
      '[id^="input-error"], .kc-feedback-text, ' +
      '.pf-c-form__helper-text.pf-m-error, .input-error'
    );
    nodes.forEach(function (el) {
      if (el.closest && el.closest(".hireai-error-banner")) return;
      var text = (el.textContent || "").replace(/\s+/g, " ").trim();
      if (!text) return;
      var key = text.toLowerCase();
      if (seen[key]) return;
      seen[key] = true;
      out.push(text);
    });
    return out;
  }

  function enhanceErrors() {
    var form = document.querySelector(
      "#kc-register-form, #kc-form-login, #kc-content form, form"
    );
    if (!form || form.querySelector(".hireai-error-banner")) return;

    var errors = collectErrors();
    if (!errors.length) return;

    var banner = document.createElement("div");
    banner.className = "hireai-error-banner";
    banner.setAttribute("role", "alert");

    var title = document.createElement("div");
    title.className = "hireai-error-title";
    title.textContent = errors.length > 1
      ? "Please fix the following before continuing"
      : "Please check your details";
    banner.appendChild(title);

    if (errors.length > 1) {
      var ul = document.createElement("ul");
      ul.className = "hireai-error-list";
      errors.forEach(function (e) {
        var li = document.createElement("li");
        li.textContent = e;
        ul.appendChild(li);
      });
      banner.appendChild(ul);
    } else {
      var single = document.createElement("div");
      single.className = "hireai-error-text";
      single.textContent = errors[0];
      banner.appendChild(single);
    }

    form.insertBefore(banner, form.firstChild);

    // The banner consolidates the global alert — hide the now-duplicate one.
    document.querySelectorAll(".alert, .pf-c-alert").forEach(function (a) {
      if (!a.closest(".hireai-error-banner")) a.style.display = "none";
    });

    banner.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }

  // ── Subtitle under the page title ─────────────────────────────────────
  function addSubtitle(isRegister) {
    var title = document.getElementById("kc-page-title")
             || document.querySelector("#kc-content h1, #kc-content h2");
    if (!title || document.querySelector(".hireai-subtitle")) return;
    var sub = document.createElement("p");
    sub.className = "hireai-subtitle";
    sub.textContent = isRegister
      ? "Create your account to apply for jobs."
      : "Welcome back — sign in to continue.";
    title.parentNode.insertBefore(sub, title.nextSibling);
  }

  function run() {
    var isRegister = !!document.getElementById("kc-register-form");
    try { addSubtitle(isRegister); } catch (e) {}
    try { enhanceErrors(); }     catch (e) {}
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", run);
  } else {
    run();
  }
})();

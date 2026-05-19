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

  // ── Phone: +216 prefix ────────────────────────────────────────────────
  function enhancePhone() {
    var phone = document.querySelector(
      'input[name="phoneNumber"], #phoneNumber, input[name="user.attributes.phoneNumber"]'
    );
    if (!phone || phone.dataset.hireaiPhone) return;
    phone.dataset.hireaiPhone = "1";

    // The stored value is +216XXXXXXXX. Show only the 8 local digits in the
    // field — the prefix is rendered separately. This strip also handles the
    // form re-rendering after a validation error (value comes back as +216…).
    phone.value = phone.value.replace(/^\+?216/, "").replace(/\D/g, "").slice(0, 8);
    phone.type = "tel";
    phone.setAttribute("maxlength", "8");
    phone.setAttribute("inputmode", "numeric");
    phone.setAttribute("autocomplete", "tel-national");
    phone.placeholder = "20 123 456";

    var group = document.createElement("div");
    group.className = "hireai-phone-group";
    var prefix = document.createElement("span");
    prefix.className = "hireai-phone-prefix";
    prefix.textContent = "+216";
    phone.parentNode.insertBefore(group, phone);
    group.appendChild(prefix);
    group.appendChild(phone);

    // Keep the field digits-only, max 8.
    phone.addEventListener("input", function () {
      var clean = phone.value.replace(/\D/g, "").slice(0, 8);
      if (clean !== phone.value) phone.value = clean;
    });

    // On submit, prepend +216 so the value matches the realm's ^\+216\d{8}$.
    // If the user left it incomplete, submit as-is and let the server reject it.
    if (phone.form) {
      phone.form.addEventListener("submit", function () {
        var digits = phone.value.replace(/\D/g, "");
        if (digits.length === 8) phone.value = "+216" + digits;
      });
    }
  }

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
    try { enhancePhone(); }      catch (e) {}
    try { addSubtitle(isRegister); } catch (e) {}
    try { enhanceErrors(); }     catch (e) {}
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", run);
  } else {
    run();
  }
})();

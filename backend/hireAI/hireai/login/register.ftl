<#import "template.ftl" as layout>
<#import "user-profile-commons.ftl" as userProfileCommons>
<#import "register-commons.ftl" as registerCommons>
<@layout.registrationLayout displayMessage=messagesPerField.exists('global') displayRequiredFields=true; section>
    <#if section = "header">
        <#if messageHeader??>
            ${kcSanitize(msg("${messageHeader}"))?no_esc}
        <#else>
            ${msg("registerTitle")}
        </#if>
    <#elseif section = "form">

        <#-- Step indicator for the client-side registration wizard -->
        <ol class="reg-steps">
            <li class="is-active"><span class="reg-dot">1</span><span class="reg-label">Account</span></li>
            <li><span class="reg-dot">2</span><span class="reg-label">Details</span></li>
            <li><span class="reg-dot">3</span><span class="reg-label">Password</span></li>
        </ol>

        <form id="kc-register-form" class="${properties.kcFormClass!}" action="${url.registrationAction}" method="post">

            <@userProfileCommons.userProfileFormFields; callback, attribute>
                <#if callback = "afterField">
                <#-- render password fields just under the username or email (if used as username) -->
                    <#if passwordRequired?? && (attribute.name == 'username' || (attribute.name == 'email' && realm.registrationEmailAsUsername))>
                        <div class="${properties.kcFormGroupClass!}">
                            <div class="${properties.kcLabelWrapperClass!}">
                                <label for="password" class="${properties.kcLabelClass!}">${msg("password")}</label> *
                            </div>
                            <div class="${properties.kcInputWrapperClass!}">
                                <div class="${properties.kcInputGroup!}" dir="ltr">
                                    <input type="password" id="password" class="${properties.kcInputClass!}" name="password"
                                           autocomplete="new-password"
                                           aria-invalid="<#if messagesPerField.existsError('password','password-confirm')>true</#if>"
                                    />
                                    <button class="${properties.kcFormPasswordVisibilityButtonClass!}" type="button" aria-label="${msg('showPassword')}"
                                            aria-controls="password"  data-password-toggle
                                            data-icon-show="${properties.kcFormPasswordVisibilityIconShow!}" data-icon-hide="${properties.kcFormPasswordVisibilityIconHide!}"
                                            data-label-show="${msg('showPassword')}" data-label-hide="${msg('hidePassword')}">
                                        <i class="${properties.kcFormPasswordVisibilityIconShow!}" aria-hidden="true"></i>
                                    </button>
                                </div>

                                <#if messagesPerField.existsError('password')>
                                    <span id="input-error-password" class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
		                                ${kcSanitize(messagesPerField.get('password'))?no_esc}
		                            </span>
                                </#if>
                            </div>
                        </div>

                        <div class="${properties.kcFormGroupClass!}">
                            <div class="${properties.kcLabelWrapperClass!}">
                                <label for="password-confirm"
                                       class="${properties.kcLabelClass!}">${msg("passwordConfirm")}</label> *
                            </div>
                            <div class="${properties.kcInputWrapperClass!}">
                                <div class="${properties.kcInputGroup!}" dir="ltr">
                                    <input type="password" id="password-confirm" class="${properties.kcInputClass!}"
                                           name="password-confirm" autocomplete="new-password"
                                           aria-invalid="<#if messagesPerField.existsError('password-confirm')>true</#if>"
                                    />
                                    <button class="${properties.kcFormPasswordVisibilityButtonClass!}" type="button" aria-label="${msg('showPassword')}"
                                            aria-controls="password-confirm"  data-password-toggle
                                            data-icon-show="${properties.kcFormPasswordVisibilityIconShow!}" data-icon-hide="${properties.kcFormPasswordVisibilityIconHide!}"
                                            data-label-show="${msg('showPassword')}" data-label-hide="${msg('hidePassword')}">
                                        <i class="${properties.kcFormPasswordVisibilityIconShow!}" aria-hidden="true"></i>
                                    </button>
                                </div>

                                <#if messagesPerField.existsError('password-confirm')>
                                    <span id="input-error-password-confirm" class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
		                                ${kcSanitize(messagesPerField.get('password-confirm'))?no_esc}
		                            </span>
                                </#if>
                            </div>
                        </div>
                    </#if>
                </#if>
            </@userProfileCommons.userProfileFormFields>

            <@registerCommons.termsAcceptance/>

            <#if recaptchaRequired?? && (recaptchaVisible!false)>
                <div class="form-group">
                    <div class="${properties.kcInputWrapperClass!}">
                        <div class="g-recaptcha" data-size="compact" data-sitekey="${recaptchaSiteKey}" data-action="${recaptchaAction}"></div>
                    </div>
                </div>
            </#if>

            <div class="${properties.kcFormGroupClass!}">
                <div id="kc-form-options" class="${properties.kcFormOptionsClass!}">
                    <div class="${properties.kcFormOptionsWrapperClass!}">
                        <span><a href="${url.loginUrl}">${kcSanitize(msg("backToLogin"))?no_esc}</a></span>
                    </div>
                </div>

                <#if recaptchaRequired?? && !(recaptchaVisible!false)>
                    <script>
                        function onSubmitRecaptcha(token) {
                            document.getElementById("kc-register-form").requestSubmit();
                        }
                    </script>
                    <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!} g-recaptcha"
                            data-sitekey="${recaptchaSiteKey}" data-callback='onSubmitRecaptcha' data-action='${recaptchaAction}' type="submit">
                            ${msg("doRegister")}
                        </button>
                    </div>
                <#else>
                    <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                        <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}" type="submit" value="${msg("doRegister")}"/>
                    </div>
                </#if>
            </div>
        </form>

        <#-- ── Client-side registration wizard ──
             Keycloak registration is one server-side POST. This splits the
             single form into 3 visual steps, validates each step before
             advancing, and only the last step submits. The server still
             does the full validation on submit. -->
        <script>
          (function () {
            var form = document.getElementById('kc-register-form');
            if (!form) return;

            function topGroup(el) {
              while (el && el.parentElement !== form) el = el.parentElement;
              return el;
            }
            function groupOfId(id) {
              var el = document.getElementById(id);
              return el ? topGroup(el) : null;
            }

            var accountStep  = ['username', 'email'].map(groupOfId).filter(Boolean);
            var passwordStep = ['password', 'password-confirm'].map(groupOfId).filter(Boolean);

            var submitInput = form.querySelector('input[type="submit"], button[type="submit"]');
            var buttonsGroup = submitInput ? topGroup(submitInput) : null;

            var assigned = accountStep.concat(passwordStep);
            var detailsStep = [];
            Array.prototype.forEach.call(form.children, function (child) {
              if (child === buttonsGroup) return;
              if (assigned.indexOf(child) !== -1) return;
              detailsStep.push(child);
            });

            // Password is intentionally the LAST step.
            var steps = [accountStep, detailsStep, passwordStep];

            // Build the Back / Next nav row; move the real submit button into it.
            var nav = document.createElement('div');
            nav.className = 'reg-nav';
            var backBtn = document.createElement('button');
            backBtn.type = 'button'; backBtn.className = 'reg-back'; backBtn.textContent = 'Back';
            var nextBtn = document.createElement('button');
            nextBtn.type = 'button'; nextBtn.className = 'reg-next'; nextBtn.textContent = 'Next';
            nav.appendChild(backBtn);
            nav.appendChild(nextBtn);
            if (submitInput) nav.appendChild(submitInput);
            if (buttonsGroup) { form.insertBefore(nav, buttonsGroup); } else { form.appendChild(nav); }

            var indicators = document.querySelectorAll('.reg-steps > li');

            // Phone number: the user types only the 8 local digits — the
            // +216 country code is a fixed prefix, prepended on submit.
            var phone = form.querySelector('input[name="phoneNumber"]');
            if (phone) {
              phone.setAttribute('data-reg-phone', '1');
              phone.removeAttribute('pattern');
              phone.setAttribute('inputmode', 'numeric');
              phone.setAttribute('maxlength', '8');
              phone.setAttribute('placeholder', '12345678');
              // strip any pre-filled +216 (e.g. after a server-side bounce-back)
              phone.value = phone.value.replace(/^\+?216/, '').replace(/\D/g, '').slice(0, 8);
              phone.addEventListener('input', function () {
                phone.value = phone.value.replace(/\D/g, '').slice(0, 8);
              });
              // wrap the input with a fixed "+216" prefix box
              var phoneWrap = document.createElement('div');
              phoneWrap.className = 'reg-phone';
              var phonePre = document.createElement('span');
              phonePre.className = 'reg-phone-prefix';
              phonePre.textContent = '+216';
              phone.parentNode.insertBefore(phoneWrap, phone);
              phoneWrap.appendChild(phonePre);
              phoneWrap.appendChild(phone);
              // prepend the country code right before the form submits
              form.addEventListener('submit', function () {
                var d = phone.value.replace(/\D/g, '');
                if (d.length > 8 && d.slice(0, 3) === '216') { d = d.slice(3); }
                d = d.slice(0, 8);
                phone.value = d ? ('+216' + d) : '';
              });
            }

            function isRequired(inp) {
              if (inp.required) return true;
              if (inp.getAttribute('aria-required') === 'true') return true;
              if (inp.getAttribute('data-reg-phone') === '1') return true;
              if (inp.id === 'password' || inp.id === 'password-confirm') return true;
              // Keycloak marks user-profile fields as required with a bare
              // "*" text node next to the label — not an attribute on the
              // input — so detect required-ness from that marker.
              if (inp.id) {
                var lbl = document.querySelector('label[for="' + inp.id + '"]');
                if (lbl && lbl.parentElement
                    && lbl.parentElement.textContent.indexOf('*') !== -1) {
                  return true;
                }
              }
              return false;
            }

            // Place the inline error after the input — or after the input
            // group wrapper when the field has an addon (the password eye).
            function errorAnchor(inp) {
              var p = inp.parentElement;
              if (p && p.classList && p.classList.contains('reg-phone')) return p;
              return (p && p.querySelector('button')) ? p : inp;
            }
            function clearError(inp) {
              inp.classList.remove('reg-invalid');
              var anchor = errorAnchor(inp);
              anchor.classList.remove('reg-invalid');
              var next = anchor.nextElementSibling;
              if (next && next.classList && next.classList.contains('reg-error-msg')) {
                next.parentNode.removeChild(next);
              }
            }
            function showError(inp, msg) {
              clearError(inp);
              inp.classList.add('reg-invalid');
              var anchor = errorAnchor(inp);
              anchor.classList.add('reg-invalid');
              var span = document.createElement('span');
              span.className = 'reg-error-msg';
              span.textContent = msg;
              anchor.insertAdjacentElement('afterend', span);
            }

            // Returns an error message for the field, or null when valid.
            function fieldError(inp) {
              if (inp.type === 'hidden' || inp.disabled) return null;
              var v = inp.value.trim();
              if (isRequired(inp) && !v) return 'This field is required.';
              if (inp.getAttribute('data-reg-phone') === '1') {
                if (v && !/^\d{8}$/.test(v)) return 'Enter the 8 digits of your phone number.';
                return null;
              }
              if (v && !inp.checkValidity()) return inp.validationMessage || 'Invalid value.';
              if (inp.id === 'email' && v && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v)) {
                return 'Enter a valid email address.';
              }
              if (inp.id === 'password-confirm') {
                var p = document.getElementById('password');
                if (p && v && p.value !== v) return 'Passwords do not match.';
              }
              return null;
            }
            function validateField(inp) {
              var err = fieldError(inp);
              if (err) { showError(inp, err); return false; }
              clearError(inp);
              return true;
            }
            function validateStep(idx) {
              var fields = [];
              steps[idx].forEach(function (g) {
                Array.prototype.push.apply(fields, g.querySelectorAll('input, select, textarea'));
              });
              var firstBad = null;
              fields.forEach(function (inp) {
                if (!validateField(inp) && !firstBad) { firstBad = inp; }
              });
              if (firstBad) { try { firstBad.focus(); } catch (e) {} return false; }
              return true;
            }

            // Live validation: check a field when focus leaves it, and
            // clear its error as soon as the user starts editing it again.
            steps.forEach(function (groups) {
              groups.forEach(function (g) {
                g.querySelectorAll('input, select, textarea').forEach(function (inp) {
                  if (inp.type === 'hidden') return;
                  inp.addEventListener('blur', function () { validateField(inp); });
                  inp.addEventListener('input', function () {
                    clearError(inp);
                    if (inp.id === 'password') {
                      var c = document.getElementById('password-confirm');
                      if (c) clearError(c);
                    }
                  });
                });
              });
            });

            var current = 0;
            function render() {
              steps.forEach(function (groups, i) {
                groups.forEach(function (g) { g.style.display = (i === current) ? '' : 'none'; });
              });
              var last = current === steps.length - 1;
              backBtn.style.display = current === 0 ? 'none' : '';
              nextBtn.style.display = last ? 'none' : '';
              if (submitInput) submitInput.style.display = last ? '' : 'none';
              indicators.forEach(function (li, i) {
                li.classList.toggle('is-active', i === current);
                li.classList.toggle('is-done', i < current);
              });
              var first = steps[current][0] && steps[current][0].querySelector('input, select, textarea');
              if (first) { try { first.focus(); } catch (e) {} }
            }
            function go(idx) {
              if (idx < 0 || idx >= steps.length) return;
              current = idx;
              render();
            }

            backBtn.addEventListener('click', function () { go(current - 1); });
            nextBtn.addEventListener('click', function () { if (validateStep(current)) go(current + 1); });

            // Enter advances to the next step instead of submitting early.
            form.addEventListener('keydown', function (e) {
              if (e.key === 'Enter' && current !== steps.length - 1
                  && e.target.tagName !== 'TEXTAREA') {
                e.preventDefault();
                if (validateStep(current)) go(current + 1);
              }
            });

            // If the server bounced back a field error, open its step.
            var startStep = 0;
            var errEl = form.querySelector('[id^="input-error-"]');
            if (errEl) {
              var g = topGroup(errEl);
              steps.forEach(function (groups, i) { if (groups.indexOf(g) !== -1) startStep = i; });
            }
            go(startStep);
          })();
        </script>
        <script type="module" src="${url.resourcesPath}/js/passwordVisibility.js"></script>
    </#if>
</@layout.registrationLayout>

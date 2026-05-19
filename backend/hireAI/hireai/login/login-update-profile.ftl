<#import "template.ftl" as layout>
<#import "user-profile-commons.ftl" as userProfileCommons>
<@layout.registrationLayout displayMessage=messagesPerField.exists('global') displayRequiredFields=true; section>
    <#if section = "header">
        ${msg("loginProfileTitle")}
    <#elseif section = "form">
        <form id="kc-update-profile-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">

            <@userProfileCommons.userProfileFormFields/>

            <div class="${properties.kcFormGroupClass!}">
                <div id="kc-form-options" class="${properties.kcFormOptionsClass!}">
                    <div class="${properties.kcFormOptionsWrapperClass!}">
                    </div>
                </div>

                <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                    <#if isAppInitiatedAction??>
                        <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonLargeClass!}" type="submit" value="${msg("doSubmit")}" />
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonLargeClass!}" type="submit" name="cancel-aia" value="true" formnovalidate>${msg("doCancel")}</button>
                    <#else>
                        <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}" type="submit" value="${msg("doSubmit")}" />
                    </#if>
                </div>
            </div>
        </form>

        <#-- ── Phone +216 prefix + inline per-field validation ──
             Same behaviour as the registration page. -->
        <script>
          (function () {
            var form = document.getElementById('kc-update-profile-form');
            if (!form) return;

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

            // Phone: the user types only the 8 local digits — +216 is a
            // fixed prefix, prepended right before the form is submitted.
            var phone = form.querySelector('input[name="phoneNumber"]');
            if (phone) {
              phone.setAttribute('data-reg-phone', '1');
              phone.removeAttribute('pattern');
              phone.setAttribute('inputmode', 'numeric');
              phone.setAttribute('maxlength', '8');
              phone.setAttribute('placeholder', '12345678');
              phone.value = phone.value.replace(/^\+?216/, '').replace(/\D/g, '').slice(0, 8);
              phone.addEventListener('input', function () {
                phone.value = phone.value.replace(/\D/g, '').slice(0, 8);
              });
              var phoneWrap = document.createElement('div');
              phoneWrap.className = 'reg-phone';
              var phonePre = document.createElement('span');
              phonePre.className = 'reg-phone-prefix';
              phonePre.textContent = '+216';
              phone.parentNode.insertBefore(phoneWrap, phone);
              phoneWrap.appendChild(phonePre);
              phoneWrap.appendChild(phone);
            }

            function isRequired(inp) {
              if (inp.required) return true;
              if (inp.getAttribute('aria-required') === 'true') return true;
              if (inp.getAttribute('data-reg-phone') === '1') return true;
              // Keycloak marks required user-profile fields with a bare "*"
              // text node next to the label — read that.
              if (inp.id) {
                var lbl = document.querySelector('label[for="' + inp.id + '"]');
                if (lbl && lbl.parentElement
                    && lbl.parentElement.textContent.indexOf('*') !== -1) {
                  return true;
                }
              }
              return false;
            }
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
              return null;
            }
            function validateField(inp) {
              var err = fieldError(inp);
              if (err) { showError(inp, err); return false; }
              clearError(inp);
              return true;
            }

            // Validate each field when focus leaves it; clear it while typing.
            form.querySelectorAll('input, select, textarea').forEach(function (inp) {
              if (inp.type === 'hidden') return;
              inp.addEventListener('blur', function () { validateField(inp); });
              inp.addEventListener('input', function () { clearError(inp); });
            });

            // On submit: validate everything, then prepend +216 to the phone.
            form.addEventListener('submit', function (e) {
              if (e.submitter && e.submitter.name === 'cancel-aia') return;
              var firstBad = null;
              form.querySelectorAll('input, select, textarea').forEach(function (inp) {
                if (!validateField(inp) && !firstBad) { firstBad = inp; }
              });
              if (firstBad) {
                e.preventDefault();
                try { firstBad.focus(); } catch (ex) {}
                return;
              }
              if (phone) {
                var d = phone.value.replace(/\D/g, '');
                if (d.length > 8 && d.slice(0, 3) === '216') { d = d.slice(3); }
                d = d.slice(0, 8);
                phone.value = d ? ('+216' + d) : '';
              }
            });
          })();
        </script>
    </#if>
</@layout.registrationLayout>

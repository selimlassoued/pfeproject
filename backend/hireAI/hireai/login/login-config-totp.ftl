<#import "template.ftl" as layout>
<@layout.registrationLayout displayRequiredFields=false displayMessage=!messagesPerField.existsError('totp','userLabel'); section>

    <#if section = "header">
        ${msg("loginTotpTitle")}
    <#elseif section = "form">
        <div class="totp-layout">

            <#-- ── Left column: the QR code (or the manual secret key) ── -->
            <div class="totp-visual">
                <#if mode?? && mode = "manual">
                    <span id="kc-totp-secret-key">${totp.totpSecretEncoded}</span>
                    <ul class="totp-manual-details">
                        <li id="kc-totp-type">${msg("loginTotpType")}: ${msg("loginTotp." + totp.policy.type)}</li>
                        <li id="kc-totp-algorithm">${msg("loginTotpAlgorithm")}: ${totp.policy.getAlgorithmKey()}</li>
                        <li id="kc-totp-digits">${msg("loginTotpDigits")}: ${totp.policy.digits}</li>
                        <#if totp.policy.type = "totp">
                            <li id="kc-totp-period">${msg("loginTotpInterval")}: ${totp.policy.period}</li>
                        <#elseif totp.policy.type = "hotp">
                            <li id="kc-totp-counter">${msg("loginTotpCounter")}: ${totp.policy.initialCounter}</li>
                        </#if>
                    </ul>
                    <a href="${totp.qrUrl}" id="mode-barcode">${msg("loginTotpScanBarcode")}</a>
                <#else>
                    <div class="totp-qr-frame">
                        <img id="kc-totp-secret-qr-code" src="data:image/png;base64, ${totp.totpSecretQrCode}" alt="QR code">
                    </div>
                    <p class="totp-visual-caption">Scan this code with your authenticator app</p>
                    <a href="${totp.manualUrl}" id="mode-manual">${msg("loginTotpUnableToScan")}</a>
                </#if>
            </div>

            <#-- ── Right column: the numbered steps + the verification form ── -->
            <div class="totp-steps">
                <ol id="kc-totp-settings">
                    <li>
                        <p>${msg("loginTotpStep1")}</p>
                        <ul id="kc-totp-supported-apps">
                            <#list totp.supportedApplications as app>
                                <li>${msg(app)}</li>
                            </#list>
                        </ul>
                    </li>
                    <li>
                        <#if mode?? && mode = "manual">
                            <p>${msg("loginTotpManualStep2")}</p>
                        <#else>
                            <p>${msg("loginTotpStep2")}</p>
                        </#if>
                    </li>
                    <li>
                        <p>${msg("loginTotpStep3")}</p>
                    </li>
                </ol>

                <form action="${url.loginAction}" class="${properties.kcFormClass!}" id="kc-totp-settings-form" method="post">
                    <div class="${properties.kcFormGroupClass!}">
                        <div class="${properties.kcInputWrapperClass!}">
                            <label for="totp" class="control-label">${msg("authenticatorCode")}</label> <span class="required">*</span>
                        </div>
                        <div class="${properties.kcInputWrapperClass!}">
                            <input type="text" id="totp" name="totp" autocomplete="one-time-code"
                                   class="${properties.kcInputClass!}"
                                   inputmode="numeric" pattern="[0-9]*" maxlength="6"
                                   aria-invalid="<#if messagesPerField.existsError('totp')>true</#if>"
                                   dir="ltr"
                            />

                            <#if messagesPerField.existsError('totp')>
                                <span id="input-error-otp-code" class="${properties.kcInputErrorMessageClass!}" aria-live="polite">
                                    ${kcSanitize(messagesPerField.get('totp'))?no_esc}
                                </span>
                            </#if>

                        </div>
                        <input type="hidden" id="totpSecret" name="totpSecret" value="${totp.totpSecret}" />
                        <#if mode??><input type="hidden" id="mode" name="mode" value="${mode}"/></#if>

                        <#-- Device name is not shown: the credential is auto-labelled. -->
                        <input type="hidden" id="userLabel" name="userLabel" value="Authenticator app" />
                    </div>

                    <#if isAppInitiatedAction??>
                        <input type="submit"
                               class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonLargeClass!}"
                               id="saveTOTPBtn" value="${msg("doSubmit")}"
                        />
                        <button type="submit"
                                class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonLargeClass!}"
                                id="cancelTOTPBtn" name="cancel-aia" value="true">${msg("doCancel")}</button>
                    <#else>
                        <input type="submit"
                               class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                               id="saveTOTPBtn" value="${msg("doSubmit")}"
                        />
                    </#if>
                </form>
            </div>

        </div>

        <#-- Keep the code field digits-only; auto-submit once 6 are entered. -->
        <script>
          (function () {
            var input = document.getElementById('totp');
            var form  = document.getElementById('kc-totp-settings-form');
            if (!input || !form) return;

            var submitted = false;
            input.addEventListener('input', function () {
              var digits = input.value.replace(/\D/g, '').slice(0, 6);
              if (digits !== input.value) { input.value = digits; }
              if (digits.length === 6 && !submitted) {
                submitted = true;
                form.submit();
              }
            });

            input.focus();
          })();
        </script>
    </#if>
</@layout.registrationLayout>

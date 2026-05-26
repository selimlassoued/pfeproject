<#import "template.ftl" as layout>
<@layout.registrationLayout; section>
    <#--
        Custom HireAI rendering of the standard Keycloak first-broker-login
        "confirm link" page (Keycloak 26 calls it login-idp-link-confirm.ftl,
        not the older idp-confirm-link.ftl).

        Fires when a candidate signs in via an IdP (Google, etc.) using an
        email that already matches a local Keycloak account. Keycloak displays
        its own "User with email X already exists" alert in the layout's
        message area — we don't render the email ourselves. Form contract
        matches the default template exactly:
          • submitAction=updateProfile → button id "updateProfile" (review)
          • submitAction=linkAccount   → button id "linkAccount" (continue)
          • hideReviewButton flag respected when the realm hides Review
        Only the visuals change.
    -->
    <#if section = "header">
        ${msg("confirmLinkIdpTitle")}
    <#elseif section = "form">

        <div class="hireai-idp-link">
            <p class="hireai-idp-link__lead">
                An account with this email already exists on HireAI. Choose
                how you'd like to continue:
            </p>

            <form id="kc-register-form" class="${properties.kcFormClass!}"
                  action="${url.loginAction}" method="post">

                <div class="hireai-idp-link__options">
                    <!-- Primary: link the IdP to the existing account. Most
                         common path — same person, different sign-in provider. -->
                    <button type="submit" name="submitAction"
                            id="linkAccount" value="linkAccount"
                            class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}">
                        ${msg("confirmLinkIdpContinue", idpDisplayName)}
                    </button>
                    <p class="hireai-idp-link__hint">
                        Recommended. Connect this sign-in method to your
                        existing HireAI account so both work going forward.
                    </p>

                    <!-- Secondary: re-verify the profile details. Hidden when
                         the realm sets hideReviewButton (same as default). -->
                    <#if !hideReviewButton?has_content>
                        <button type="submit" name="submitAction"
                                id="updateProfile" value="updateProfile"
                                class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!} hireai-idp-link__secondary">
                            ${msg("confirmLinkIdpReviewProfile")}
                        </button>
                        <p class="hireai-idp-link__hint">
                            Not sure the existing account is yours? Review and
                            edit the profile before linking.
                        </p>
                    </#if>
                </div>
            </form>
        </div>

    </#if>
</@layout.registrationLayout>

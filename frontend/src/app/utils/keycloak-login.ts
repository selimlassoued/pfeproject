import type Keycloak from 'keycloak-js';
import type { KeycloakLoginOptions } from 'keycloak-js';

/**
 * Wrap keycloak.login() so the Keycloak login/register screen renders in
 * the same theme (light/dark) the user is currently using in our app.
 *
 * Keycloak's login url builder does not expose arbitrary query params, so
 * we call createLoginUrl() to get the full url, append our custom
 * `ui_theme` flag, and navigate manually. theme-init.js in the keycloak
 * theme reads that param and sets <html data-theme="...">.
 */
export async function loginWithCurrentTheme(
  keycloak: Keycloak,
  options: KeycloakLoginOptions = {}
): Promise<void> {
  const theme = document.documentElement.getAttribute('data-theme') ?? 'dark';
  const baseUrl = await keycloak.createLoginUrl(options);
  const sep = baseUrl.includes('?') ? '&' : '?';
  window.location.href = `${baseUrl}${sep}ui_theme=${encodeURIComponent(theme)}`;
}

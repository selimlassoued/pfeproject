import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { KeycloakAccountService } from '../services/keycloak-account-service';
import { User } from '../model/user.model';
import { MatSnackBar } from '@angular/material/snack-bar';
import Keycloak from 'keycloak-js';
import { loginWithCurrentTheme } from '../utils/keycloak-login';
import { applyServerErrors, clearServerErrors, normalizeHttpError } from '../utils/http-error';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './profile.html',
  styleUrls: ['./profile.css'],
})
export class Profile implements OnInit {
  loading = true;
  saving  = false;
  error?: string;
  success?: string;

  /** Set during load() - true when the account is linked to Google/GitHub. */
  hasLinkedSocial = false;

  user?: User;
  form: FormGroup;

  private readonly keycloak = inject(Keycloak);

  constructor(
    private fb: FormBuilder,
    private account: KeycloakAccountService,
    private snackBar: MatSnackBar,
    private router: Router,
  ) {
    this.form = this.fb.group({
      username:      ['', [Validators.required, Validators.minLength(3)]],
      firstName:     ['', Validators.required],
      lastName:      ['', Validators.required],
      email:         [{ value: '', disabled: true }],
      phoneNational: ['', [Validators.required, Validators.pattern(/^\d{8}$/)]],
    });
  }

  async ngOnInit() { await this.load(); }

  async load() {
    this.loading = true;
    this.error   = undefined;
    this.success  = undefined;
    try {
      this.user = await this.account.getUser();
      const phoneNational = (this.user.phoneNumber ?? '')
        .replace(/^\+216/, '').replace(/\D/g, '').slice(0, 8);
      this.form.patchValue({
        username:      this.user.username  ?? '',
        firstName:     this.user.firstName ?? '',
        lastName:      this.user.lastName  ?? '',
        email:         this.user.email     ?? '',
        phoneNational,
      });
      this.hasLinkedSocial = await this.account.hasLinkedSocialAccount();
    } catch {
      this.error = 'Failed to load profile';
    } finally {
      this.loading = false;
    }
  }

  onPhoneInput(event: Event) {
    const input  = event.target as HTMLInputElement;
    const digits = (input.value ?? '').replace(/\D/g, '').slice(0, 8);
    this.form.get('phoneNational')?.setValue(digits, { emitEvent: false });
  }

  get isCandidate(): boolean {
    const kc = this.keycloak;
    return !kc.hasRealmRole('SUPERADMIN') && !kc.hasRealmRole('ADMIN') && !kc.hasRealmRole('RECRUITER');
  }

  /**
   * The 2-factor self-setup card is shown only to password-login candidates.
   * Staff are auto-enrolled by the Keycloak flow; social-login users bypass
   * the OTP flow entirely, so the button would not actually do anything.
   */
  get showTwoFactor(): boolean {
    return this.isCandidate && !this.hasLinkedSocial;
  }

  goToPreferences() { this.router.navigate(['/preferences']); }

  /**
   * Launch Keycloak's built-in OTP enrolment ("Configure authenticator app").
   * `maxAge: 0` forces re-authentication - the user must re-enter their
   * password before they can change a security setting like 2FA.
   */
  enableTwoFactor(): void {
    loginWithCurrentTheme(this.keycloak, {
      action: 'CONFIGURE_TOTP',
      redirectUri: window.location.origin + '/profile',
      maxAge: 0,
    });
  }

  async save() {
    if (!this.user) return;
    this.saving  = true;
    this.error   = undefined;
    this.success  = undefined;
    clearServerErrors(this.form);
    try {
      const raw    = this.form.getRawValue();
      const digits = (raw.phoneNational ?? '').replace(/\D/g, '');
      await this.account.updateUser({
        ...this.user,
        username:    raw.username.trim(),
        firstName:   raw.firstName.trim(),
        lastName:    raw.lastName.trim(),
        phoneNumber: digits.length === 8 ? `+216${digits}` : undefined,
      });
      this.success = 'Profile updated successfully.';
    } catch (e: any) {
      if (e?.status === 409) {
        this.snackBar.open('Username already taken!', 'Close', { duration: 5000, panelClass: ['error-snackbar'] });
        this.error = 'Username already taken.';
      } else {
        const httpError = normalizeHttpError(e);
        applyServerErrors(this.form, httpError.fieldErrors);
        this.error = httpError.message || 'Update failed.';
      }
    } finally {
      this.saving = false;
    }
  }

  /** Server message wins over a generic field-required default. */
  fieldError(path: string, defaultMsg: string): string | null {
    const ctl = this.form.get(path);
    if (!ctl) return null;
    if (ctl.errors?.['server']) return ctl.errors['server'] as string;
    if (ctl.touched && ctl.invalid) return defaultMsg;
    return null;
  }
}

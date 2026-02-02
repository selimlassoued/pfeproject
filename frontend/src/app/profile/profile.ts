import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { KeycloakAccountService } from '../services/keycloak-account-service';
import { User } from '../model/user.model';
import { MatSnackBar } from '@angular/material/snack-bar';
import Swal from 'sweetalert2';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './profile.html',
  styleUrls: ['./profile.css'],
})
export class Profile implements OnInit {
  loading = true;
  saving = false;
  error?: string;
  success?: string;

  user?: User;
  form: FormGroup;

  constructor(private fb: FormBuilder, private account: KeycloakAccountService,private snackBar: MatSnackBar) {
    this.form = this.fb.group({
      username: ['', [Validators.required, Validators.minLength(3)]],
      firstName: ['', Validators.required],
      lastName: ['', Validators.required],
      email: [{ value: '', disabled: true }],
      phoneNational: ['', [Validators.pattern(/^\d{8}$/)]],
    });
  }

  async ngOnInit() {
    await this.load();
  }

  async load() {
    this.loading = true;
    this.error = undefined;
    this.success = undefined;

    try {
      this.user = await this.account.getUser();

      const phoneNational = (this.user.phoneNumber ?? '')
        .replace(/^\+216/, '')        
        .replace(/\D/g, '')           
        .slice(0, 8);                 

      this.form.patchValue({
        username: this.user.username ?? '',
        firstName: this.user.firstName ?? '',
        lastName: this.user.lastName ?? '',
        email: this.user.email ?? '',
        phoneNational: phoneNational,
      });
    } catch {
      this.error = 'Failed to load profile';
    } finally {
      this.loading = false;
    }
  }

  onPhoneInput(event: Event) {
    const input = event.target as HTMLInputElement;
    const digits = (input.value ?? '').replace(/\D/g, '').slice(0, 8);
    this.form.get('phoneNational')?.setValue(digits, { emitEvent: false });
  }

  async save() {
    if (!this.user) return;

    this.saving = true;
    this.error = undefined;
    this.success = undefined;

    try {
      const raw = this.form.getRawValue() as {
        username: string;
        firstName: string;
        lastName: string;
        email: string;
        phoneNational: string;
      };

      const digits = (raw.phoneNational ?? '').replace(/\D/g, '');
      const phoneNumber = digits.length === 8 ? `+216${digits}` : undefined;

      const updated: User = {
        username: raw.username.trim(),
        firstName: raw.firstName.trim(),
        lastName: raw.lastName.trim(),
        email: this.user.email, 
        phoneNumber: phoneNumber,
      };

      await this.account.updateUser(updated);
      this.success = 'Profile updated successfully.';
    } catch (e: any) {
      if (e?.status === 409) {
        this.error = 'Username already taken.';
        this.snackBar.open('Username already taken!', 'Close', {
          duration: 5000,
          panelClass: ['error-snackbar']
        });
        Swal.fire({
          icon: 'warning',
          title: 'Are you sure?',
          showCancelButton: true,
        });

      } else {
        this.error = 'Update failed.';
      }
    } finally {
      this.saving = false;
    }
  }
}

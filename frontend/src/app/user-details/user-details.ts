import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import Swal from 'sweetalert2';
import { MatSnackBar } from '@angular/material/snack-bar';

import { UserService } from '../services/user-service';
import { AdminUserRow } from '../model/admin_users.type';

@Component({
  selector: 'app-user-details',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './user-details.html',
  styleUrl: './user-details.css',
})
export class UserDetails implements OnInit {
  loading = false;
  error: string | null = null;

  user?: AdminUserRow;

  acting = false;
  deleting = false;

  selectedMainRole: 'ADMIN' | 'RECRUITER' | null = null;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private users: UserService,
    private snack: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.load();
  }

  async load(): Promise<void> {
    this.loading = true;
    this.error = null;

    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.error = 'Missing user id.';
      this.loading = false;
      return;
    }

    try {
      this.user = await this.users.getUser(id);

      const roles = (this.user.roles ?? []).map(r => String(r).toUpperCase());
      this.selectedMainRole =
        roles.includes('ADMIN') ? 'ADMIN' :
        roles.includes('RECRUITER') ? 'RECRUITER' :
        null;

    } catch (e: any) {
      this.error = e?.error?.message ?? e?.message ?? 'Failed to load user profile.';
    } finally {
      this.loading = false;
    }
  }

  back(): void {
    this.router.navigate(['/listUsers']);
  }

  roleLabel(roles?: string[]): string {
    const r = (roles ?? []).map(x => String(x).toUpperCase());
    if (r.includes('ADMIN')) return 'ADMIN';
    if (r.includes('RECRUITER')) return 'RECRUITER';
    return 'CANDIDATE';
  }

  statusPill(enabled?: boolean): { text: string; cls: string } {
    if (enabled === false) return { text: 'Blocked', cls: 'pill-danger' };
    return { text: 'Active', cls: 'pill-success' };
  }

  formatDate(ts?: number): string {
    if (!ts) return '—';
    try {
      return new Date(ts).toLocaleString();
    } catch {
      return '—';
    }
  }

  attrsList(attrs?: Record<string, string[]>): { key: string; value: string }[] {
    if (!attrs) return [];
    return Object.entries(attrs)
      .map(([key, values]) => ({ key, value: (values ?? []).join(', ') }))
      .sort((a, b) => a.key.localeCompare(b.key));
  }

  async toggleBlock(): Promise<void> {
    if (!this.user) return;

    this.acting = true;
    this.error = null;

    try {
      const nextEnabled = !(this.user.enabled ?? true);
      await this.users.setEnabled(this.user.id, nextEnabled);
      await this.load();
    } catch (e: any) {
      this.error = e?.error?.message ?? e?.message ?? 'Failed to update status.';
    } finally {
      this.acting = false;
    }
  }

  async deleteUser(): Promise<void> {
    if (!this.user) return;

    const label = this.user.username ?? this.user.email ?? this.user.id;

    const res = await Swal.fire({
      title: 'Delete account?',
      text: `Delete "${label}"? This cannot be undone.`,
      icon: 'warning',
      showCancelButton: true,
      confirmButtonText: 'Yes, delete',
      cancelButtonText: 'Cancel',
      confirmButtonColor: '#d32f2f',
    });

    if (!res.isConfirmed) return;

    this.deleting = true;
    this.error = null;

    try {
      await this.users.deleteUser(this.user.id);

      await Swal.fire({
        title: 'Deleted',
        text: 'User account deleted successfully.',
        icon: 'success',
        timer: 1400,
        showConfirmButton: false,
      });

      this.router.navigate(['/admin/users']);
    } catch (e: any) {
      const msg = e?.error?.message ?? e?.message ?? 'Failed to delete user.';
      await Swal.fire({ title: 'Error', text: msg, icon: 'error' });
    } finally {
      this.deleting = false;
    }
  }

  async saveRoles(): Promise<void> {
    if (!this.user) return;

    this.acting = true;
    this.error = null;

    try {
      const roles: string[] = ['CANDIDATE'];
      if (this.selectedMainRole) roles.push(this.selectedMainRole);

      await this.users.updateRoles(this.user.id, roles);

      this.snack.open(' Roles updated successfully', 'OK', {
        duration: 2500,
        horizontalPosition: 'center',
        verticalPosition: 'bottom',
      });

      await this.load();
    } catch (e: any) {
      const msg = e?.error?.message ?? e?.message ?? 'Failed to update roles.';
      this.error = msg;

      this.snack.open(`❌ ${msg}`, 'Close', {
        duration: 3000,
        horizontalPosition: 'center',
        verticalPosition : 'bottom',
      });
    } finally {
      this.acting = false;
    }
  }

  clearMainRole(): void {
    this.selectedMainRole = null;
  }
}

import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import Swal from 'sweetalert2';
import { MatSnackBar } from '@angular/material/snack-bar';
import Keycloak from 'keycloak-js';

import { UserService } from '../services/user-service';
import { ApplicationService } from '../services/application.service';
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


  moderationStatus: 'FLAGGED' | 'BLOCKED' | 'CLEAR' = 'CLEAR';
  moderationLoading = false;

  lastFlaggedBy: string | null = null;
  private currentUserId: string = '';

  private readonly keycloak = inject(Keycloak);

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private users: UserService,
    private appService: ApplicationService,
    private snack: MatSnackBar
  ) {}

  ngOnInit(): void { this.load(); }

  async load(): Promise<void> {
    this.loading = true;
    this.error = null;

    const id = this.route.snapshot.paramMap.get('id');
    if (!id) { this.error = 'Missing user id.'; this.loading = false; return; }

    try {
      this.user = await this.users.getUser(id);
      this.currentUserId = this.keycloak.subject ?? '';

      if (this.isViewedUserCandidate()) {
        await this.loadModerationStatus(id);
        if (this.isFlagged()) await this.loadFlaggedBy(id);
      }
    } catch (e: any) {
      this.error = e?.error?.message ?? e?.message ?? 'Failed to load user profile.';
    } finally {
      this.loading = false;
    }
  }

  private async loadModerationStatus(candidateUserId: string): Promise<void> {
    this.moderationLoading = true;
    try {
      const status = await this.appService.getCandidateModerationStatus(candidateUserId).toPromise();
      this.moderationStatus = (status?.status as any) ?? 'CLEAR';
    } catch { this.moderationStatus = 'CLEAR'; }
    finally { this.moderationLoading = false; }
  }

  private async loadFlaggedBy(candidateUserId: string): Promise<void> {
    try {
      const result = await this.appService.getLastFlaggedBy(candidateUserId).toPromise();
      this.lastFlaggedBy = result?.flaggedBy ?? null;
    } catch { this.lastFlaggedBy = null; }
  }

  /** Open the dedicated candidate-history page for this candidate. */
  viewApplicationsHistory(): void {
    if (this.user?.id) {
      this.router.navigate(['/candidate', this.user.id, 'history']);
    }
  }

  // ── Logged-in user role helpers ───────────────────────────────────────────

  isSuperAdmin(): boolean { return this.keycloak.hasRealmRole('SUPERADMIN'); }
  isAdmin():      boolean { return this.keycloak.hasRealmRole('ADMIN') && !this.isSuperAdmin(); }
  isRecruiter():  boolean { return this.keycloak.hasRealmRole('RECRUITER') && !this.isAdmin() && !this.isSuperAdmin(); }

  // ── Viewed user role helpers ──────────────────────────────────────────────

  viewedUserRole(): string {
    const roles = (this.user?.roles ?? []).map(r => String(r).toUpperCase());
    if (roles.includes('SUPERADMIN')) return 'SUPERADMIN';
    if (roles.includes('ADMIN'))      return 'ADMIN';
    if (roles.includes('RECRUITER'))  return 'RECRUITER';
    return 'CANDIDATE';
  }

  isViewedUserCandidate():  boolean { return this.viewedUserRole() === 'CANDIDATE';  }
  isViewedUserRecruiter():  boolean { return this.viewedUserRole() === 'RECRUITER';  }
  isViewedUserAdmin():      boolean { return this.viewedUserRole() === 'ADMIN';      }
  isViewedUserSuperAdmin(): boolean { return this.viewedUserRole() === 'SUPERADMIN'; }

  // ── Permission helpers ────────────────────────────────────────────────────

  /**
   * Can the logged-in user block/unblock the viewed user?
   *
   * SUPERADMIN → can block ADMIN, RECRUITER, CANDIDATE (not SUPERADMIN)
   * ADMIN      → can block RECRUITER, CANDIDATE only (not ADMIN, not SUPERADMIN)
   * RECRUITER  → cannot block anyone
   */
  canBlock(): boolean {
    if (this.isViewedUserSuperAdmin()) return false; // nobody can block SUPERADMIN
    if (this.isSuperAdmin()) return true;            // SUPERADMIN can block anyone else
    if (this.isAdmin()) {
      // ADMIN can only block RECRUITER or CANDIDATE
      return this.isViewedUserRecruiter() || this.isViewedUserCandidate();
    }
    return false;
  }

  /**
   * Can the logged-in user dismiss the signal on this candidate?
   * ADMIN + SUPERADMIN only, and only on candidates.
   */
  canDismiss(): boolean {
    return (this.isAdmin() || this.isSuperAdmin()) && this.isViewedUserCandidate();
  }

  /**
   * Can the logged-in user signal this candidate?
   * RECRUITER only - ADMIN and SUPERADMIN block directly.
   * Cannot signal if already FLAGGED or BLOCKED.
   */
  canSignal(): boolean {
    return this.isRecruiter()
        && this.isViewedUserCandidate()
        && this.isClear(); // cannot signal if already flagged or blocked
  }

  /**
   * Can the logged-in user unsignal this candidate?
   * RECRUITER only - and only if they are the one who flagged.
   */
  canUnsignal(): boolean {
    return this.isRecruiter()
        && this.isViewedUserCandidate()
        && this.isFlagged()
        && this.isCurrentRecruiterTheSignaler();
  }


  // ── Moderation state ──────────────────────────────────────────────────────

  isFlagged(): boolean { return this.moderationStatus === 'FLAGGED'; }
  isBlocked(): boolean { return this.moderationStatus === 'BLOCKED'; }
  isClear():   boolean { return this.moderationStatus === 'CLEAR'; }

  isCurrentRecruiterTheSignaler(): boolean {
    return this.isFlagged()
        && this.lastFlaggedBy !== null
        && this.lastFlaggedBy === this.currentUserId;
  }

  // ── Display helpers ───────────────────────────────────────────────────────

  back(): void { this.router.navigate(['/listUsers']); }

  roleLabel(roles?: string[]): string {
    const r = (roles ?? []).map(x => String(x).toUpperCase());
    if (r.includes('SUPERADMIN')) return 'SUPERADMIN';
    if (r.includes('ADMIN'))      return 'ADMIN';
    if (r.includes('RECRUITER'))  return 'RECRUITER';
    return 'CANDIDATE';
  }

  statusPill(enabled?: boolean): { text: string; cls: string } {
    if (enabled === false) return { text: 'Blocked', cls: 'pill-danger' };
    return { text: 'Active', cls: 'pill-success' };
  }

  moderationPill(): { text: string; cls: string } | null {
    if (this.moderationStatus === 'FLAGGED') return { text: 'Flagged', cls: 'pill-flagged' };
    if (this.moderationStatus === 'BLOCKED') return { text: 'Blocked', cls: 'pill-danger' };
    return null;
  }

  formatDate(ts?: number): string {
    if (!ts) return '-';
    try { return new Date(ts).toLocaleString(); } catch { return '-'; }
  }

  // ── Block / Unblock ───────────────────────────────────────────────────────

  async toggleBlock(): Promise<void> {
    if (!this.user || !this.canBlock()) return;

    const nextEnabled = !(this.user.enabled ?? true);
    const action      = nextEnabled ? 'unblock' : 'block';
    const actionTitle = nextEnabled ? 'Unblock'  : 'Block';

    const result = await Swal.fire({
      title: `${actionTitle} user?`,
      text: `Please provide a reason for ${action}ing this user:`,
      input: 'text',
      inputPlaceholder: 'Enter reason (optional)',
      inputValue: '',
      showCancelButton: true,
      confirmButtonText: `Yes, ${action}`,
      cancelButtonText: 'Cancel',
      confirmButtonColor: nextEnabled ? '#4caf50' : '#d32f2f',
    });

    if (!result.isConfirmed) return;

    const reason = result.value?.trim() || undefined;
    this.acting = true;
    this.error = null;

    try {
      await this.users.setEnabled(this.user.id, nextEnabled, reason);
      await Swal.fire({
        title: 'Success', text: `User ${action}ed successfully.`,
        icon: 'success', timer: 1500, showConfirmButton: false,
      });
      await this.load();
    } catch (e: any) {
      const errorMsg = e?.error?.message ?? e?.message ?? `Failed to ${action} user.`;
      this.error = errorMsg;
      await Swal.fire({ title: 'Error', text: errorMsg, icon: 'error' });
    } finally {
      this.acting = false;
    }
  }

  // ── Dismiss signal - ADMIN + SUPERADMIN ──────────────────────────────────

  async dismissSignal(): Promise<void> {
    if (!this.user || !this.canDismiss()) return;

    const result = await Swal.fire({
      title: 'Dismiss signal?',
      text: 'This will restore all flagged applications to their previous status. The candidate was not blocked.',
      showCancelButton: true,
      confirmButtonText: 'Yes, dismiss',
      cancelButtonText: 'Cancel',
      confirmButtonColor: '#f59e0b',
    });

    if (!result.isConfirmed) return;

    this.acting = true;
    this.error = null;

    try {
      await this.users.dismissSignal(this.user.id);
      this.snack.open('Signal dismissed - applications restored', 'OK', { duration: 2500 });
      await this.load();
    } catch (e: any) {
      this.error = e?.error?.message ?? 'Failed to dismiss signal.';
    } finally {
      this.acting = false;
    }
  }

  // ── Signal candidate - RECRUITER only ────────────────────────────────────

  async signalCandidate(): Promise<void> {
    if (!this.user || !this.canSignal()) return;

    const result = await Swal.fire({
      title: ' Signal candidate?',
      html: `<p style="font-size:.9rem">
        This will flag <strong>${this.user.firstName ?? this.user.username}</strong>'s
        applications and notify the admin for review.
      </p>`,
      input: 'textarea',
      inputPlaceholder: 'Describe the issue (e.g. Fake CV, spam applications…)',
      inputAttributes: { rows: '3' },
      showCancelButton: true,
      confirmButtonText: 'Confirm Signal',
      cancelButtonText: 'Cancel',
      confirmButtonColor: '#f59e0b',
      inputValidator: (value) => {
        if (!value?.trim()) return 'Please provide a reason.';
        return null;
      },
    });

    if (!result.isConfirmed) return;

    const reason = result.value.trim();
    this.acting = true;
    this.error = null;

    try {
      await this.appService.signalCandidate(this.user.id, reason).toPromise();
      await Swal.fire({
        title: 'Candidate signaled',
        text: 'The admin has been notified.',
        icon: 'success', timer: 1500, showConfirmButton: false,
      });
      await this.load();
    } catch (e: any) {
      const msg = e?.error?.message ?? 'Failed to signal candidate.';
      this.error = msg;
      await Swal.fire({ title: 'Error', text: msg, icon: 'error' });
    } finally {
      this.acting = false;
    }
  }

  // ── Unsignal - RECRUITER (own signal only) ────────────────────────────────

  async unsignalCandidate(): Promise<void> {
    if (!this.user) return;

    const result = await Swal.fire({
      title: 'Remove signal?',
      input: 'text',
      inputPlaceholder: 'Reason for removing the signal',
      showCancelButton: true,
      confirmButtonText: 'Yes, remove signal',
      cancelButtonText: 'Cancel',
      confirmButtonColor: '#4caf50',
      inputValidator: (value) => {
        if (!value?.trim()) return 'Please provide a reason.';
        return null;
      },
    });

    if (!result.isConfirmed) return;

    const reason = result.value.trim();
    this.acting = true;
    this.error = null;

    try {
      await this.appService.unsignalCandidate(this.user.id, reason).toPromise();
      await Swal.fire({
        title: 'Signal removed',
        text: 'Applications have been restored.',
        icon: 'success', timer: 1500, showConfirmButton: false,
      });
      await this.load();
    } catch (e: any) {
      const msg = e?.error?.message ?? 'Failed to remove signal.';
      this.error = msg;
      await Swal.fire({ title: 'Error', text: msg, icon: 'error' });
    } finally {
      this.acting = false;
    }
  }


}
import {
  Component,
  OnDestroy,
  OnInit,
  HostListener,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, takeUntil } from 'rxjs/operators';
import Swal from 'sweetalert2';
import Keycloak from 'keycloak-js';

import { UserService } from '../services/user-service';
import { AdminUserRow } from '../model/admin_users.type';
import { matchesWordStart } from '../utils/suggestion-match';
import { normalizeHttpError } from '../utils/http-error';

type EnabledFilter = 'ALL' | 'ENABLED' | 'DISABLED';
type RoleFilter    = 'ALL' | string;

@Component({
  selector: 'app-list-users',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './list-users.html',
  styleUrl: './list-users.css',
})
export class ListUsers implements OnInit, OnDestroy {
  private readonly keycloak = inject(Keycloak);

  loading  = false;
  error: string | null = null;
  acting   = false;

  private all:      AdminUserRow[] = [];
  private filtered: AdminUserRow[] = [];
  users: AdminUserRow[] = [];

  search        = '';
  enabledFilter: EnabledFilter = 'ALL';
  roleFilter:    RoleFilter    = 'ALL';
  rolesOptions:  string[]      = [];

  /** Set to true on Enter in the search box so the datalist popup
   *  stops covering the table. Reset when the field is cleared. */
  suppressSearchSuggest = false;

  apiMax = 200;
  pageIndex    = 0;
  pageSize     = 20;
  totalPages   = 0;
  totalElements = 0;
  pagerWindow  = 3;
  rowsOpen     = false;
  rowsOptions  = [10, 20, 50];

  private currentUserId = '';
  private destroy$    = new Subject<void>();
  private refreshKey$ = new Subject<string>();

  constructor(
    private adminUsers: UserService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  // ── Role helpers ──────────────────────────────────────────────────────────

  isSuperAdmin(): boolean { return this.keycloak.hasRealmRole('SUPERADMIN'); }
  isAdmin():      boolean { return this.keycloak.hasRealmRole('ADMIN') && !this.isSuperAdmin(); }
  isRecruiter():  boolean { return this.keycloak.hasRealmRole('RECRUITER') && !this.isAdmin() && !this.isSuperAdmin(); }

  ngOnInit(): void {
    this.currentUserId = this.keycloak.subject ?? '';
    const filter = this.route.snapshot.queryParamMap.get('filter');
    if (filter === 'DISABLED') this.enabledFilter = 'DISABLED';

    // RECRUITER sees only CANDIDATE accounts
    if (this.isRecruiter() && !this.isAdmin() && !this.isSuperAdmin()) {
      this.roleFilter = 'CANDIDATE';
    }

    this.refreshKey$
      .pipe(debounceTime(200), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => { this.pageIndex = 0; this.load(); });

    this.load();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  @HostListener('document:click', ['$event'])
  onDocClick(ev: MouseEvent) {
    if (!(ev.target as HTMLElement).closest('.rows-dd')) this.rowsOpen = false;
  }

  @HostListener('document:keydown.escape')
  onEsc() { this.rowsOpen = false; }

  onSearchChange(v: string) {
    this.search = v;
    if (!v) this.suppressSearchSuggest = false;
    this.emitRefresh();
  }

  /** Up to 10 suggestions matching whatever the user has typed.
   *  Label is "Name (email)" when both exist, else falls back to name
   *  or username. Cap keeps the popup short on large user lists. */
  get searchSuggestions(): string[] {
    const q = this.search.trim().toLowerCase();
    const seen = new Set<string>();
    const out: string[] = [];
    for (const u of this.all) {
      const name = `${u.firstName || ''} ${u.lastName || ''}`.trim();
      const id   = u.username || '';
      const email = u.email || '';
      const label = name && email ? `${name} (${email})`
                  : name ? name
                  : email || id;
      if (!label || seen.has(label)) continue;
      seen.add(label);
      out.push(label);
    }
    let result = out.sort((a, b) => a.localeCompare(b));
    if (q) result = result.filter(s => matchesWordStart(s, q));
    return result.slice(0, 10);
  }
  onFiltersChange() {
    // RECRUITER cannot change role filter - always CANDIDATE
    if (this.isRecruiter() && !this.isAdmin() && !this.isSuperAdmin()) {
      this.roleFilter = 'CANDIDATE';
    }
    this.emitRefresh();
  }
  refresh()                  {
    this.pageIndex = 0;
    this.suppressSearchSuggest = false;
    this.load(true);
  }
  toggleRows()               { this.rowsOpen = !this.rowsOpen; }

  setPageSize(size: number) {
    if (this.pageSize === size) { this.rowsOpen = false; return; }
    this.pageSize = size;
    this.rowsOpen = false;
    this.pageIndex = 0;
    this.applyFiltersAndPaginate();
  }

  private emitRefresh() { this.refreshKey$.next(this.currentKey()); }
  private currentKey()  { return `${this.search.trim()}|${this.enabledFilter}|${this.roleFilter}|${this.apiMax}`; }

  async load(forceReload = false): Promise<void> {
    this.loading = true;
    this.error = null;
    try {
      if (forceReload || this.all.length === 0) {
        const fetched = await this.adminUsers.listUsers({ first: 0, max: this.apiMax, search: this.search });
        this.all = fetched ?? [];
      }

      const rolesSet = new Set<string>();
      for (const u of this.all) {
        const r = (u.role ?? '').trim();
        if (r && r !== '-') rolesSet.add(r);
        for (const rr of (u.roles ?? [])) {
          const x = String(rr).toUpperCase().trim();
          if (x) rolesSet.add(x);
        }
      }
      this.rolesOptions = Array.from(rolesSet).sort((a, b) => a.localeCompare(b));
      this.applyFiltersAndPaginate();
    } catch (e: any) {
      this.error = e?.error?.message ?? e?.message ?? 'Failed to load users.';
    } finally {
      this.loading = false;
    }
  }

  /** Returns the single main role of a user (highest in hierarchy) */
  private mainRole(u: AdminUserRow): string {
    const roles = (u.roles ?? []).map(r => String(r).toUpperCase());
    if (roles.includes('SUPERADMIN')) return 'SUPERADMIN';
    if (roles.includes('ADMIN'))      return 'ADMIN';
    if (roles.includes('RECRUITER'))  return 'RECRUITER';
    return 'CANDIDATE';
  }

  private applyFiltersAndPaginate() {
    const q = this.search.trim().toLowerCase();

    this.filtered = (this.all ?? []).filter(u => {
      const enabledOk =
        this.enabledFilter === 'ALL'      ? true :
        this.enabledFilter === 'ENABLED'  ? u.enabled !== false :
                                            u.enabled === false;

      // Use mainRole() to avoid CANDIDATE matching everyone
      const roleOk =
        this.roleFilter === 'ALL' ? true :
        this.mainRole(u) === String(this.roleFilter).toUpperCase();

      // ADMIN can only see RECRUITER and CANDIDATE - not other ADMINs or SUPERADMIN
      const visibilityOk = this.isSuperAdmin() ? true
        : this.isAdmin() ? ['RECRUITER', 'CANDIDATE'].includes(this.mainRole(u))
        : this.mainRole(u) === 'CANDIDATE'; // RECRUITER sees only CANDIDATE

      // Never show the logged-in user themselves
      const notSelf = u.id !== this.currentUserId;

      const fullName = `${u.firstName || ''} ${u.lastName || ''}`.trim();
      const hay = `${u.username || ''} ${fullName} ${u.email || ''} ${fullName} (${u.email || ''})`.toLowerCase();
      const searchOk = !q || hay.includes(q);

      return enabledOk && roleOk && visibilityOk && notSelf && searchOk;
    });

    this.totalElements = this.filtered.length;
    this.totalPages = Math.max(1, Math.ceil(this.totalElements / this.pageSize));
    if (this.pageIndex >= this.totalPages) this.pageIndex = this.totalPages - 1;
    if (this.pageIndex < 0) this.pageIndex = 0;

    const from = this.pageIndex * this.pageSize;
    this.users = this.filtered.slice(from, Math.min(from + this.pageSize, this.filtered.length));
  }

  goToPage(p: number) {
    if (p < 0 || p >= this.totalPages || p === this.pageIndex) return;
    this.pageIndex = p;
    this.applyFiltersAndPaginate();
  }

  first() { this.goToPage(0); }
  last()  { this.goToPage(this.totalPages - 1); }
  prev()  { this.goToPage(this.pageIndex - 1); }
  next()  { this.goToPage(this.pageIndex + 1); }

  pages(): number[] {
    const total = this.totalPages, cur = this.pageIndex, w = this.pagerWindow;
    if (total <= w) return Array.from({ length: total }, (_, i) => i);
    let start = cur - Math.floor(w / 2), end = start + w - 1;
    if (start < 0)       { start = 0;         end = w - 1; }
    if (end >= total)    { end = total - 1;    start = total - w; }
    const arr: number[] = [];
    for (let i = start; i <= end; i++) arr.push(i);
    return arr;
  }

  goToUser(u: AdminUserRow) { this.router.navigate(['/user', u.id]); }

  formatDate(ts?: number): string {
    if (!ts) return '-';
    try { return new Date(ts).toLocaleString(); } catch { return '-'; }
  }

  statusPill(enabled?: boolean): { text: string; cls: string } {
    if (enabled === false) return { text: 'Disabled', cls: 'pill-danger' };
    return { text: 'Enabled', cls: 'pill-success' };
  }

  // ── Create user ───────────────────────────────────────────────────────────

  async openCreateUser(): Promise<void> {
    // Determine which role(s) this actor can create
    const canCreateAdmin = this.isSuperAdmin();
    const roleOptions = canCreateAdmin
      ? '<option value="RECRUITER">RECRUITER</option><option value="ADMIN">ADMIN</option>'
      : '<option value="RECRUITER">RECRUITER</option>';

    const inputStyle = 'width:100%;background:rgba(255,255,255,0.05);border:1px solid rgba(121,164,233,0.28);border-radius:12px;color:#f8fafc;padding:.7rem .9rem;font-size:.93rem;font-family:Montserrat,sans-serif;outline:none;box-sizing:border-box;margin:0';
    const labelStyle = 'font-size:.75rem;font-weight:700;color:rgba(121,164,233,0.7);text-transform:uppercase;letter-spacing:.08em;display:block;margin-bottom:.35rem';

    const { value: formValues, isConfirmed } = await Swal.fire({
      title: `Create ${canCreateAdmin ? 'Admin / Recruiter' : 'Recruiter'} Account`,
      background: 'linear-gradient(160deg,rgba(18,24,52,0.98),rgba(11,16,38,0.99))',
      color: '#f8fafc',
      html: `
        <div style="display:grid;gap:.85rem;text-align:left;margin-top:.25rem">
          <div>
            <label style="${labelStyle}">Email *</label>
            <input id="swal-em" type="email" placeholder="email@vermeg.com" style="${inputStyle}">
          </div>
          <div>
            <label style="${labelStyle}">Role *</label>
            <select id="swal-role" style="${inputStyle};background:#1b2236;cursor:pointer">
              ${roleOptions}
            </select>
          </div>
          <p style="font-size:.8rem;color:rgba(121,164,233,0.55);margin:0">
            An invitation email will be sent - they'll set their password and
            complete their profile (name, phone) on first sign-in.
          </p>
        </div>
      `,
      showCancelButton: true,
      confirmButtonText: 'Create & Send Invite',
      cancelButtonText: 'Cancel',
      confirmButtonColor: '#1e40bc',
      cancelButtonColor: 'transparent',
      customClass: {
        popup: 'swal-hireai-popup',
        title: 'swal-hireai-title',
        confirmButton: 'swal-hireai-confirm',
        cancelButton: 'swal-hireai-cancel',
        actions: 'swal-hireai-actions',
      },
      preConfirm: () => {
        const em    = (document.getElementById('swal-em')   as HTMLInputElement).value.trim();
        const role  = (document.getElementById('swal-role') as HTMLSelectElement).value;

        if (!em) {
          Swal.showValidationMessage('Email is required.');
          return false;
        }
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(em)) {
          Swal.showValidationMessage('Please enter a valid email address.');
          return false;
        }
        return { email: em, role };
      },
    });

    if (!isConfirmed || !formValues) return;

    this.acting = true;
    try {
      await this.adminUsers.createUser(formValues.email, formValues.role);
      await Swal.fire({
        title: 'Account created!',
        html: `<p style="color:rgba(255,255,255,0.7)">
          An invitation email has been sent to <strong>${formValues.email}</strong>.
          They can set their password and access the platform.
        </p>`,
        icon: 'success',
        timer: 3000,
        showConfirmButton: false,
      });
      await this.load(true);
    } catch (e: any) {
      const httpError = normalizeHttpError(e);
      // If the backend sent field-level errors, list them under the
      // main message so the recruiter sees exactly which field failed.
      const fieldList = Object.entries(httpError.fieldErrors)
        .map(([field, msg]) => `<li><strong>${field}</strong>: ${msg}</li>`)
        .join('');
      const html = fieldList
        ? `<p style="margin:0 0 .5rem 0">${httpError.message}</p><ul style="text-align:left;margin:0;padding-left:1.25rem">${fieldList}</ul>`
        : `<p style="margin:0">${httpError.message}</p>`;
      await Swal.fire({ title: 'Error', html, icon: 'error' });
    } finally {
      this.acting = false;
    }
  }

}
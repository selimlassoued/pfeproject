import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, takeUntil } from 'rxjs/operators';

import { UserService } from '../services/user-service';
import { AdminUserRow } from '../model/admin_users.type';

type EnabledFilter = 'ALL' | 'ENABLED' | 'DISABLED';
type RoleFilter = 'ALL' | string;

@Component({
  selector: 'app-list-users',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './list-users.html',
  styleUrl: './list-users.css',
})
export class ListUsers implements OnInit, OnDestroy {
  loading = false;
  error: string | null = null;

  users: AdminUserRow[] = [];

  // UI state
  search = '';
  max = 20;

  enabledFilter: EnabledFilter = 'ALL';
  roleFilter: RoleFilter = 'ALL';

  // roles list for the dropdown (derived from response)
  rolesOptions: string[] = [];

  // live refresh trigger
  private refresh$ = new Subject<void>();
  private destroy$ = new Subject<void>();

  constructor(private adminUsers: UserService, private router: Router) {}

  ngOnInit(): void {
    // live debounce for search + filters
    this.refresh$
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => this.load());

    this.load();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ✅ called on typing
  onSearchChange(value: string): void {
    this.search = value;
    this.triggerRefresh();
  }

  // ✅ called on dropdown change
  onFiltersChange(): void {
    this.triggerRefresh();
  }

  private triggerRefresh(): void {
    // distinctUntilChanged() needs values; easiest is to emit a string key
    // but we already used Subject<void>. We'll just debounce with a separate key:
    this.refreshKey.next(this.currentKey());
  }

  // ---- tiny helper for distinctUntilChanged ----
  private refreshKey = new Subject<string>();

  private currentKey(): string {
    return `${this.search.trim()}|${this.enabledFilter}|${this.roleFilter}|${this.max}`;
  }

  private initRefreshPipeOnce = false;

  private ensureRefreshPipe(): void {
    if (this.initRefreshPipeOnce) return;
    this.initRefreshPipeOnce = true;

    this.refreshKey
      .pipe(debounceTime(350), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => this.load());
  }

  async load(): Promise<void> {
    this.ensureRefreshPipe();

    this.loading = true;
    this.error = null;

    try {
      const all = await this.adminUsers.listUsers({
        first: 0,
        max: this.max,
        search: this.search,
      });

      // build role dropdown options from response
      const roles = new Set<string>();
      for (const u of all) {
        const r = (u.role ?? '').trim();
        if (r && r !== '—') roles.add(r);
        for (const rr of (u.roles ?? [])) {
          const x = String(rr).toUpperCase().trim();
          if (x) roles.add(x);
        }
      }
      this.rolesOptions = Array.from(roles).sort((a, b) => a.localeCompare(b));

      // apply filters client-side
      this.users = all.filter(u => {
        // enabled filter
        const enabledOk =
          this.enabledFilter === 'ALL'
            ? true
            : this.enabledFilter === 'ENABLED'
              ? (u.enabled !== false)
              : (u.enabled === false);

        // role filter
        const roleOk =
          this.roleFilter === 'ALL'
            ? true
            : (String(u.role ?? '').toUpperCase() === String(this.roleFilter).toUpperCase())
              || (u.roles ?? []).some(r => String(r).toUpperCase() === String(this.roleFilter).toUpperCase());

        return enabledOk && roleOk;
      });
    } catch (e: any) {
      this.error =
        e?.error?.message ??
        e?.message ??
        'Failed to load users. Check gateway logs.';
    } finally {
      this.loading = false;
    }
  }

  // keep your manual buttons working too
  async onSearch(): Promise<void> {
    await this.load();
  }

  goToUser(u: AdminUserRow): void {
    this.router.navigate(['/user', u.id]);
  }

  formatDate(ts?: number): string {
    if (!ts) return '—';
    try {
      return new Date(ts).toLocaleString();
    } catch {
      return '—';
    }
  }

  statusPill(enabled?: boolean): { text: string; cls: string } {
    if (enabled === false) return { text: 'Disabled', cls: 'pill-danger' };
    return { text: 'Enabled', cls: 'pill-success' };
  }

  // ✅ call once from template (ngOnInit already loads)
  ngAfterViewInit(): void {
    // connect UI changes to refreshKey pipeline
    this.ensureRefreshPipe();
  }
}

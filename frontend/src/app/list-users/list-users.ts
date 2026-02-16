import {
  Component,
  OnDestroy,
  OnInit,
  HostListener,
} from '@angular/core';
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

  // raw data from API (up to apiMax)
  private all: AdminUserRow[] = [];

  // after filters
  private filtered: AdminUserRow[] = [];

  // displayed page
  users: AdminUserRow[] = [];

  // UI state
  search = '';
  enabledFilter: EnabledFilter = 'ALL';
  roleFilter: RoleFilter = 'ALL';
  rolesOptions: string[] = [];

  // server fetch cap (Keycloak endpoint uses first/max)
  apiMax = 200;

  // pagination
  pageIndex = 0; // 0-based
  pageSize = 20;
  totalPages = 0;
  totalElements = 0;

  // compact pager window
  pagerWindow = 3;

  // custom dropdown (Rows)
  rowsOpen = false;
  rowsOptions = [10, 20, 50];

  private destroy$ = new Subject<void>();
  private refreshKey$ = new Subject<string>();

  constructor(private adminUsers: UserService, private router: Router) {}

  ngOnInit(): void {
    this.refreshKey$
      .pipe(debounceTime(350), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => {
        this.pageIndex = 0;
        this.load();
      });

    this.load();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // close dropdown on outside click / escape
  @HostListener('document:click', ['$event'])
  onDocClick(ev: MouseEvent) {
    const target = ev.target as HTMLElement;
    // close if clicking outside rows dropdown
    if (!target.closest('.rows-dd')) this.rowsOpen = false;
  }

  @HostListener('document:keydown.escape')
  onEsc() {
    this.rowsOpen = false;
  }

  // ============ UI events ============
  onSearchChange(v: string) {
    this.search = v;
    this.emitRefresh();
  }

  onFiltersChange() {
    this.emitRefresh();
  }

  refresh() {
    // manual button: refresh from API
    this.pageIndex = 0;
    this.load(true);
  }

  toggleRows() {
    this.rowsOpen = !this.rowsOpen;
  }

  setPageSize(size: number) {
    if (this.pageSize === size) {
      this.rowsOpen = false;
      return;
    }
    this.pageSize = size;
    this.rowsOpen = false;
    this.pageIndex = 0;
    this.applyFiltersAndPaginate();
  }

  private emitRefresh() {
    this.refreshKey$.next(this.currentKey());
  }

  private currentKey(): string {
    return `${this.search.trim()}|${this.enabledFilter}|${this.roleFilter}|${this.apiMax}`;
  }

  // ============ loading ============
  async load(forceReload = false): Promise<void> {
    this.loading = true;
    this.error = null;

    try {
      if (forceReload || this.all.length === 0) {
        const fetched = await this.adminUsers.listUsers({
          first: 0,
          max: this.apiMax,
          search: this.search, // server-side search
        });

        this.all = fetched ?? [];
      }

      // Build role options from current loaded set
      const rolesSet = new Set<string>();
      for (const u of this.all) {
        const r = (u.role ?? '').trim();
        if (r && r !== '—') rolesSet.add(r);

        for (const rr of (u.roles ?? [])) {
          const x = String(rr).toUpperCase().trim();
          if (x) rolesSet.add(x);
        }
      }
      this.rolesOptions = Array.from(rolesSet).sort((a, b) => a.localeCompare(b));

      this.applyFiltersAndPaginate();
    } catch (e: any) {
      this.error =
        e?.error?.message ??
        e?.message ??
        'Failed to load users. Check gateway logs.';
    } finally {
      this.loading = false;
    }
  }

  // ============ filtering + pagination ============
  private applyFiltersAndPaginate() {
    // client-side filters on loaded results
    this.filtered = (this.all ?? []).filter((u) => {
      // enabled
      const enabledOk =
        this.enabledFilter === 'ALL'
          ? true
          : this.enabledFilter === 'ENABLED'
          ? u.enabled !== false
          : u.enabled === false;

      // role
      const roleOk =
        this.roleFilter === 'ALL'
          ? true
          : String(u.role ?? '').toUpperCase() === String(this.roleFilter).toUpperCase() ||
            (u.roles ?? []).some(
              (r) => String(r).toUpperCase() === String(this.roleFilter).toUpperCase()
            );

      return enabledOk && roleOk;
    });

    this.totalElements = this.filtered.length;
    this.totalPages = Math.max(1, Math.ceil(this.totalElements / this.pageSize));

    // clamp pageIndex
    if (this.pageIndex >= this.totalPages) this.pageIndex = this.totalPages - 1;
    if (this.pageIndex < 0) this.pageIndex = 0;

    const from = this.pageIndex * this.pageSize;
    const to = Math.min(from + this.pageSize, this.filtered.length);
    this.users = this.filtered.slice(from, to);
  }

  goToPage(p: number) {
    if (p < 0 || p >= this.totalPages || p === this.pageIndex) return;
    this.pageIndex = p;
    this.applyFiltersAndPaginate();
  }

  first() { this.goToPage(0); }
  last() { this.goToPage(this.totalPages - 1); }
  prev() { this.goToPage(this.pageIndex - 1); }
  next() { this.goToPage(this.pageIndex + 1); }

  // show a small window but keep access to first/last via buttons + ellipsis
  pages(): number[] {
    const total = this.totalPages;
    const cur = this.pageIndex;
    const w = this.pagerWindow;

    if (total <= w) return Array.from({ length: total }, (_, i) => i);

    let start = cur - Math.floor(w / 2);
    let end = start + w - 1;

    if (start < 0) {
      start = 0;
      end = w - 1;
    }
    if (end >= total) {
      end = total - 1;
      start = total - w;
    }

    const arr: number[] = [];
    for (let i = start; i <= end; i++) arr.push(i);
    return arr;
  }

  // ============ row actions ============
  goToUser(u: AdminUserRow) {
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
}

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { RouterModule } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { JobService } from '../services/job.service';           // adjust path
import { ApplicationService } from '../services/application.service'; // adjust path
import { UserService } from '../services/user-service';         // adjust path
import { JobOffer } from '../model/jobOffer.model';             // adjust path
import { ApplicationDto } from '../model/application.dto';     // adjust path
import { AdminUserRow } from '../model/admin_users.type';       // adjust path
import { PageResponse } from '../model/page-response';         // adjust path

/* ── local models ── */
export interface AuditLog {
  eventId: string;
  eventType: string;
  reason: string | null;
  actorUserId: string;
  producer: string;
  createdAt: string;
  occurredAt: string;
  targetId: string | null;
  targetType: string | null;
  changes: string | Record<string, any> | null;
}
export interface AuditStats {
  total: number; applicationUpdates: number;
  userBlocks: number; userUnblocks: number; jobUpdates: number;
}
// Use AdminUserRow for users (already has id, firstName, lastName, email, enabled)
export type KcUser = AdminUserRow;
export interface EventMeta { label: string; color: string; bg: string; }

/* system-generated reasons that should be treated as "no reason" */
const SYSTEM_REASONS = new Set([
  'blocked by admin', 'unblocked by admin',
  'unblocked from dashboard', 'blocked from dashboard',
  'he is not connected',
]);

function isSystemReason(r: string | null): boolean {
  if (!r) return true;
  return SYSTEM_REASONS.has(r.trim().toLowerCase());
}


@Component({
  selector: 'app-admin-dashboard',
  imports: [CommonModule, RouterModule],
  templateUrl: './admin-dashboard.html',
  styleUrl: './admin-dashboard.css',
})
export class AdminDashboard implements OnInit {
  private API = 'http://localhost:8888';

  stats: AuditStats | null = null;
  users: KcUser[] = [];
  blockedUsers: KcUser[] = [];
  jobs: JobOffer[] = [];
  appTotal: number | null = null;
  topJobs: { title: string; count: number; jobId: string }[] = [];

  logs: AuditLog[] = [];
  totalLogs = 0;
  page = 0;
  readonly PAGE_SIZE = 8;

  activeFilter = 'ALL';
  activeRange = 'overall';
  statsLoading = false;
  loading = true;

  /** expanded log eventId */
  expandedLogId: string | null = null;

  /** caches: userId → "First Last" */
  actorNameCache: Record<string, string> = {};
  /** caches: targetId → display name */
  targetNameCache: Record<string, string> = {};

  readonly TIME_RANGES = [
    { key: 'week', label: 'Last Week'  },
    { key: 'month', label: 'Last Month' },
    { key: 'year', label: 'Last Year'  },
    { key: 'overall', label: 'Overall'  },
  ];
  readonly FILTERS = [
    { key: 'ALL',                       label: 'All',         color: '#79a4e9' },
    { key: 'APPLICATION_STATUS_UPDATE', label: 'App Updates', color: '#79a4e9' },
    { key: 'USER_BLOCK',                label: 'Blocks',      color: '#f87171' },
    { key: 'USER_UNBLOCK',              label: 'Unblocks',    color: '#4ade80' },
    { key: 'JOB_UPDATED',               label: 'Jobs',        color: '#fbbf24' },
  ];
  readonly EVENT_META: Record<string, EventMeta> = {
    APPLICATION_STATUS_UPDATE: { label: 'App Update',     color: '#79a4e9', bg: 'rgba(121,164,233,0.12)' },
    USER_BLOCK:                { label: 'User Blocked',   color: '#f87171', bg: 'rgba(248,113,113,0.12)' },
    USER_UNBLOCK:              { label: 'User Unblocked', color: '#4ade80', bg: 'rgba(74,222,128,0.12)'  },
    JOB_UPDATED:               { label: 'Job Updated',    color: '#fbbf24', bg: 'rgba(251,191,36,0.12)'  },
  };
  readonly BREAKDOWN = [
    { key: 'applicationUpdates', label: 'App Updates', color: '#79a4e9' },
    { key: 'jobUpdates',         label: 'Job Updates', color: '#fbbf24' },
    { key: 'userBlocks',         label: 'Blocks',      color: '#f87171' },
    { key: 'userUnblocks',       label: 'Unblocks',    color: '#4ade80' },
  ];

  constructor(
    private http: HttpClient,
    private jobService: JobService,
    private appService: ApplicationService,
    private userService: UserService,
  ) {}

  ngOnInit(): void {
    this.loadSidebarData();
    this.loadStats();
    this.loadLogs();
    this.loadTopJobs();
  }

  private get headers(): HttpHeaders {
    const token = localStorage.getItem('access_token') || '';
    return new HttpHeaders({ Authorization: `Bearer ${token}` });
  }
  private get rangeParam(): string {
    return this.activeRange !== 'overall' ? `&range=${this.activeRange}` : '';
  }

  loadSidebarData(): void {
    forkJoin({
      users: this.http.get<PageResponse<KcUser>>(`${this.API}/api/admin/users/paged?page=0&size=100`, { headers: this.headers }).pipe(catchError(() => of(null))),
      jobs:  this.jobService.getAllJobs().pipe(catchError(() => of([]))),
      apps:  this.http.get<PageResponse<any>>(`${this.API}/api/applications/paged?page=0&size=1`, { headers: this.headers }).pipe(catchError(() => of(null))),
    }).subscribe(({ users, jobs, apps }) => {
      this.users        = users?.content || [];
      this.blockedUsers = this.users.filter(u => !u.enabled);
      this.jobs         = Array.isArray(jobs) ? jobs : [];
      this.appTotal     = apps?.totalElements ?? null;
      this.loading      = false;
      // pre-populate actor name cache from loaded users
      for (const u of this.users) {
        this.actorNameCache[u.id] = `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim() || u.email || u.id.slice(0, 8);
      }
    });
  }

  loadTopJobs(): void {
    this.appService.listApplicationsPaged({ page: 0, size: 500 })
      .pipe(catchError(() => of(null)))
      .subscribe(data => {
        if (!data) return;
        const counts: Record<string, { title: string; count: number; jobId: string }> = {};
        for (const app of data.content) {
          const key = app.jobId;
          if (!counts[key]) counts[key] = { title: app.jobTitle || 'Untitled', count: 0, jobId: app.jobId };
          counts[key].count++;
        }
        this.topJobs = Object.values(counts).sort((a, b) => b.count - a.count).slice(0, 5);
      });
  }

  loadStats(): void {
    this.statsLoading = true;
    this.http.get<AuditStats>(`${this.API}/api/audit/stats?${this.rangeParam}`, { headers: this.headers })
      .pipe(catchError(() => of(null)))
      .subscribe(s => { this.stats = s; this.statsLoading = false; });
  }

  loadLogs(): void {
    const eq = this.activeFilter !== 'ALL' ? `&eventType=${this.activeFilter}` : '';
    this.http.get<PageResponse<AuditLog>>(
      `${this.API}/api/audit/logs?page=${this.page}&size=${this.PAGE_SIZE}${eq}${this.rangeParam}`,
      { headers: this.headers }
    ).pipe(catchError(() => of({ content: [], totalElements: 0 } as any)))
     .subscribe(d => {
       this.logs = d.content || [];
       this.totalLogs = d.totalElements || 0;
       this.resolveNames(this.logs);
     });
  }

  /** Resolve actor + target names for a batch of logs */
  private resolveNames(logs: AuditLog[]): void {
    for (const log of logs) {
      // actor name
      if (log.actorUserId && log.actorUserId !== 'SYSTEM' && !this.actorNameCache[log.actorUserId]) {
        this.http.get<{ email: string; firstName: string; lastName: string }>(
          `${this.API}/api/admin/internal/users/${log.actorUserId}/email`, { headers: this.headers }
        ).pipe(catchError(() => of(null))).subscribe(u => {
          if (u) {
            this.actorNameCache[log.actorUserId] =
              `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim() || u.email || log.actorUserId.slice(0, 8);
          }
        });
      }
      // target name
      if (log.targetId && !this.targetNameCache[log.targetId]) {
        this.resolveTargetName(log);
      }
    }
  }

  private resolveTargetName(log: AuditLog): void {
    if (!log.targetId) return;
    const producer = (log.producer || '').toLowerCase();

    if (producer.includes('job')) {
      // job target
      this.jobService.getJobById(log.targetId)
        .pipe(catchError(() => of(null)))
        .subscribe(j => {
          if (j) this.targetNameCache[log.targetId!] = j.title;
        });
    } else if (producer.includes('application')) {
      // application target
      this.appService.getOne(log.targetId)
        .pipe(catchError(() => of(null)))
        .subscribe(a => {
          if (a) this.targetNameCache[log.targetId!] = a.jobTitle || `App #${log.targetId!.slice(0, 6)}`;
        });
    } else {
      // user target (gateway / block / unblock)
      this.http.get<{ email: string; firstName: string; lastName: string }>(
        `${this.API}/api/admin/internal/users/${log.targetId}/email`, { headers: this.headers }
      ).pipe(catchError(() => of(null))).subscribe(u => {
        if (u) {
          this.targetNameCache[log.targetId!] =
            `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim() || u.email || log.targetId!.slice(0, 8);
        }
      });
    }
  }

  /* ── helpers ── */

  getActorName(log: AuditLog): string {
    if (!log.actorUserId || log.actorUserId === 'SYSTEM') return 'System';
    return this.actorNameCache[log.actorUserId] || log.actorUserId.slice(0, 8) + '…';
  }

  getTargetName(log: AuditLog): string | null {
    if (!log.targetId) return null;
    return this.targetNameCache[log.targetId] || log.targetId.slice(0, 8) + '…';
  }

  /** Returns the reason to display, or null if it should be hidden */
  getDisplayReason(log: AuditLog): string | null {
    // For APPLICATION_STATUS_UPDATE never show reason
    if (log.eventType === 'APPLICATION_STATUS_UPDATE') return null;
    if (isSystemReason(log.reason)) return null;
    return log.reason;
  }

  toggleLog(log: AuditLog): void {
    this.expandedLogId = this.expandedLogId === log.eventId ? null : log.eventId;
  }

  isExpanded(log: AuditLog): boolean {
    return this.expandedLogId === log.eventId;
  }

  getChangesEntries(log: AuditLog): { key: string; oldVal: string | null; newVal: string | null; simple: string | null }[] {
    if (!log.changes) return [];

    // The backend stores changes as a JSON string — always parse it
    let raw: any = log.changes;
    if (typeof raw === 'string') {
      try { raw = JSON.parse(raw); } catch { return []; }
    }
    // After parsing, raw might STILL be a string (double-encoded) — parse again
    if (typeof raw === 'string') {
      try { raw = JSON.parse(raw); } catch { return []; }
    }
    if (!raw || typeof raw !== 'object') return [];

    return Object.entries(raw as Record<string, any>).map(([key, value]) => {
      if (value && typeof value === 'object' && ('old' in value || 'new' in value)) {
        // { status: { old: "APPLIED", new: "INTERVIEW_PHASE" } }
        return {
          key,
          oldVal: value['old'] != null ? String(value['old']) : null,
          newVal: value['new'] != null ? String(value['new']) : null,
          simple: null,
        };
      }
      // flat value
      return {
        key,
        oldVal: null,
        newVal: null,
        simple: typeof value === 'object' ? JSON.stringify(value) : String(value ?? ''),
      };
    });
  }

  unblockUser(userId: string, event: Event): void {
    event.stopPropagation();
    // send null body so no default reason is attached
    this.userService.setEnabled(userId, true)
      .then(() => {
        this.blockedUsers = this.blockedUsers.filter(u => u.id !== userId);
        const u = this.users.find(u => u.id === userId);
        if (u) u.enabled = true;
      })
      .catch(() => {});
  }

  /** Called by stat cards for Block/App/Job update counts — sets filter and scrolls to audit log */
  scrollToAuditFilter(filter: string): void {
    this.setFilter(filter);
    setTimeout(() => {
      const el = document.getElementById('audit-log-section');
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 100);
  }

  setRange(key: string): void { this.activeRange = key; this.page = 0; this.loadStats(); this.loadLogs(); }
  setFilter(key: string): void { this.activeFilter = key; this.page = 0; this.loadLogs(); }
  prevPage(): void { if (this.page > 0) { this.page--; this.loadLogs(); } }
  nextPage(): void { if (this.page + 1 < this.totalPages) { this.page++; this.loadLogs(); } }

  get totalPages(): number { return Math.ceil(this.totalLogs / this.PAGE_SIZE) || 1; }
  get maxTopJobCount(): number { return this.topJobs[0]?.count || 1; }

  getEventMeta(type: string): EventMeta {
    return this.EVENT_META[type] || { label: type, color: '#79a4e9', bg: 'rgba(121,164,233,0.12)' };
  }
  getBreakdownPct(key: string): number {
    if (!this.stats || this.stats.total === 0) return 0;
    return Math.round(((this.stats as any)[key] / this.stats.total) * 100);
  }
  getBreakdownVal(key: string): number { return (this.stats as any)?.[key] || 0; }
  getUserInitials(u: KcUser): string {
    // AdminUserRow has optional firstName/lastName
    return (((u.firstName ?? '')[0] ?? '') + ((u.lastName ?? '')[0] ?? '')).toUpperCase() || '?';
  }
  getUserHue(u: KcUser): number { return ((u.id ?? '').charCodeAt(0) || 60) * 37 % 360; }
  timeAgo(d: string): string {
    const diff = Date.now() - new Date(d).getTime();
    const m = Math.floor(diff / 60000);
    if (m < 1) return 'just now';
    if (m < 60) return `${m}m ago`;
    const h = Math.floor(m / 60);
    if (h < 24) return `${h}h ago`;
    return `${Math.floor(h / 24)}d ago`;
  }
  formatDate(d: string): string {
    return new Date(d).toLocaleString('en-GB', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  }
  trackById(_: number, item: any): string { return item.id || item.eventId; }
}
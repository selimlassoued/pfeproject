import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Router, RouterModule } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import Keycloak from 'keycloak-js';
import { JobService } from '../services/job.service';
import { ApplicationService } from '../services/application.service';
import { UserService } from '../services/user-service';
import { JobOffer } from '../model/jobOffer.model';
import { AdminUserRow } from '../model/admin_users.type';
import { PageResponse } from '../model/page-response';

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
  total: number;
  applicationUpdates: number;
  userBlocks: number;
  userUnblocks: number;
  jobUpdates: number;
  candidateFlagged: number;
  candidateUnflagged: number;
}
export type KcUser = AdminUserRow;
export interface EventMeta { label: string; color: string; bg: string; }

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
  private readonly keycloak = inject(Keycloak);

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
  activeSubFilter = 'ALL';
  activeRange = 'overall';
  statsLoading = false;
  loading = true;

  expandedLogId: string | null = null;
  actorNameCache: Record<string, string> = {};
  targetNameCache: Record<string, string> = {};

  // ── Role helpers ──────────────────────────────────────────────────────────

  isSuperAdmin(): boolean { return this.keycloak.hasRealmRole('SUPERADMIN'); }
  isAdmin():      boolean { return this.keycloak.hasRealmRole('ADMIN'); }
  isRecruiter():  boolean { return this.keycloak.hasRealmRole('RECRUITER') && !this.isAdmin() && !this.isSuperAdmin(); }

  /**
   * Dashboard title based on role
   */
  get callerRole(): string {
    if (this.isSuperAdmin()) return 'SUPERADMIN';
    if (this.isAdmin())      return 'ADMIN';
    return 'RECRUITER';
  }

  get dashboardTitle(): string {
    return this.isSuperAdmin() ? 'SuperAdmin Dashboard' : 'Admin Dashboard';
  }

  get dashboardSub(): string {
    if (this.isSuperAdmin()) return 'Full platform overview — all roles activity · HireAI';
    if (this.isAdmin())      return 'Recruiter & candidate activity overview · HireAI';
    return 'Your recruitment activity overview · HireAI';
  }

  // ── Filters — SUPERADMIN sees all, ADMIN sees recruiter/candidate only ────

  readonly TIME_RANGES = [
    { key: 'week',    label: 'Last Week'  },
    { key: 'month',   label: 'Last Month' },
    { key: 'year',    label: 'Last Year'  },
    { key: 'overall', label: 'Overall'    },
  ];

  // SUPERADMIN filters — everything
  private readonly FILTERS_SUPERADMIN = [
    { key: 'ALL',                        label: 'All',            color: '#79a4e9' },
    { key: 'APPLICATION_STATUS_UPDATE',  label: 'App Updates',    color: '#79a4e9' },
    { key: 'APPLICATION_WITHDRAWN',      label: 'Withdrawn',      color: '#94a3b8' },
    { key: 'BLOCKS',                     label: 'Blocks',         color: '#f87171' },
    { key: 'FLAGS',                      label: 'Flags',          color: '#fbbf24' },
    { key: 'CANDIDATE_SIGNAL_DISMISSED', label: 'Dismissed',      color: '#67e8f9' },
    { key: 'JOBS',                       label: 'Jobs',           color: '#fb923c' },
  ];

  // RECRUITER filters — only recruitment events
  private readonly FILTERS_RECRUITER = [
    { key: 'ALL',                        label: 'All',            color: '#79a4e9' },
    { key: 'APPLICATION_STATUS_UPDATE',  label: 'App Updates',    color: '#79a4e9' },
    { key: 'APPLICATION_WITHDRAWN',      label: 'Withdrawn',      color: '#94a3b8' },
    { key: 'JOBS',                       label: 'Jobs',           color: '#fb923c' },
    { key: 'FLAGS',                      label: 'Flags',          color: '#fbbf24' },
  ];

  // ADMIN filters — recruiter + candidate actions only (no ROLE_UPDATE)
  private readonly FILTERS_ADMIN = [
    { key: 'ALL',                        label: 'All',            color: '#79a4e9' },
    { key: 'APPLICATION_STATUS_UPDATE',  label: 'App Updates',    color: '#79a4e9' },
    { key: 'APPLICATION_WITHDRAWN',      label: 'Withdrawn',      color: '#94a3b8' },
    { key: 'BLOCKS',                     label: 'Blocks',         color: '#f87171' },
    { key: 'FLAGS',                      label: 'Flags',          color: '#fbbf24' },
    { key: 'CANDIDATE_SIGNAL_DISMISSED', label: 'Dismissed',      color: '#67e8f9' },
    { key: 'JOBS',                       label: 'Jobs',           color: '#fb923c' },
  ];

  get FILTERS() {
    if (this.isSuperAdmin()) return this.FILTERS_SUPERADMIN;
    if (this.isAdmin())      return this.FILTERS_ADMIN;
    return this.FILTERS_RECRUITER;
  }

  // Secondary filters — revealed only when a grouped category chip is active.
  readonly SUBFILTERS: Record<string, { key: string; label: string }[]> = {
    JOBS: [
      { key: 'ALL',               label: 'All jobs' },
      { key: 'JOB_CREATED',       label: 'Created' },
      { key: 'JOB_UPDATED',       label: 'Edited' },
      { key: 'JOB_CLOSED',        label: 'Closed' },
      { key: 'JOB_QUOTA_REACHED', label: 'Quota reached' },
    ],
    FLAGS: [
      { key: 'ALL',                 label: 'All flags' },
      { key: 'CANDIDATE_FLAGGED',   label: 'Flagged' },
      { key: 'CANDIDATE_UNFLAGGED', label: 'Unflagged' },
    ],
    BLOCKS: [
      { key: 'ALL',          label: 'All' },
      { key: 'USER_BLOCK',   label: 'Blocked' },
      { key: 'USER_UNBLOCK', label: 'Unblocked' },
    ],
  };

  get currentSubFilters(): { key: string; label: string }[] {
    return this.SUBFILTERS[this.activeFilter] ?? [];
  }

  readonly EVENT_META: Record<string, EventMeta> = {
    APPLICATION_STATUS_UPDATE:  { label: 'App Update',        color: '#79a4e9', bg: 'rgba(121,164,233,0.12)' },
    APPLICATION_WITHDRAWN:      { label: 'Withdrawn',         color: '#94a3b8', bg: 'rgba(148,163,184,0.12)' },
    USER_BLOCK:                 { label: 'User Blocked',      color: '#f87171', bg: 'rgba(248,113,113,0.12)' },
    USER_UNBLOCK:               { label: 'User Unblocked',    color: '#4ade80', bg: 'rgba(74,222,128,0.12)'  },
    CANDIDATE_FLAGGED:          { label: 'Candidate Flagged', color: '#fbbf24', bg: 'rgba(251,191,36,0.12)'  },
    CANDIDATE_UNFLAGGED:        { label: 'Signal Removed',    color: '#a78bfa', bg: 'rgba(167,139,250,0.12)' },
    CANDIDATE_SIGNAL_DISMISSED: { label: 'Signal Dismissed',  color: '#67e8f9', bg: 'rgba(103,232,249,0.12)' },
    JOB_CREATED:                { label: 'Job Created',       color: '#34d399', bg: 'rgba(52,211,153,0.12)'  },
    JOB_UPDATED:                { label: 'Job Updated',       color: '#fb923c', bg: 'rgba(251,146,60,0.12)'  },
    JOB_CLOSED:                 { label: 'Job Closed',        color: '#f87171', bg: 'rgba(248,113,113,0.12)' },
    JOB_QUOTA_REACHED:          { label: 'Quota Reached',     color: '#22d3ee', bg: 'rgba(34,211,238,0.12)'  },
  };

  // SUPERADMIN breakdown — includes role updates
  private readonly BREAKDOWN_SUPERADMIN = [
    { key: 'applicationUpdates', label: 'App Updates',  color: '#79a4e9' },
    { key: 'jobUpdates',         label: 'Job Updates',  color: '#fb923c' },
    { key: 'userBlocks',         label: 'Blocks',       color: '#f87171' },
    { key: 'userUnblocks',       label: 'Unblocks',     color: '#4ade80' },
    { key: 'candidateFlagged',   label: 'Flagged',      color: '#fbbf24' },
    { key: 'candidateUnflagged', label: 'Unflagged',    color: '#a78bfa' },
  ];

  // ADMIN breakdown — same but without role updates
  private readonly BREAKDOWN_ADMIN = [
    { key: 'applicationUpdates', label: 'App Updates',  color: '#79a4e9' },
    { key: 'jobUpdates',         label: 'Job Updates',  color: '#fb923c' },
    { key: 'userBlocks',         label: 'Blocks',       color: '#f87171' },
    { key: 'userUnblocks',       label: 'Unblocks',     color: '#4ade80' },
    { key: 'candidateFlagged',   label: 'Flagged',      color: '#fbbf24' },
    { key: 'candidateUnflagged', label: 'Unflagged',    color: '#a78bfa' },
  ];

  private readonly BREAKDOWN_RECRUITER = [
    { key: 'applicationUpdates', label: 'App Updates',  color: '#79a4e9' },
    { key: 'jobUpdates',         label: 'Job Updates',  color: '#fb923c' },
    { key: 'candidateFlagged',   label: 'Flagged',      color: '#fbbf24' },
    { key: 'candidateUnflagged', label: 'Unflagged',    color: '#a78bfa' },
  ];

  get BREAKDOWN() {
    if (this.isSuperAdmin()) return this.BREAKDOWN_SUPERADMIN;
    if (this.isAdmin())      return this.BREAKDOWN_ADMIN;
    return this.BREAKDOWN_RECRUITER;
  }

  constructor(
    private http: HttpClient,
    private jobService: JobService,
    private appService: ApplicationService,
    private userService: UserService,
    private router: Router,
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
          if (app.status === 'WITHDRAWN') continue; // exclude withdrawn
          const key = app.jobId;
          if (!counts[key]) counts[key] = { title: app.jobTitle || 'Untitled', count: 0, jobId: app.jobId };
          counts[key].count++;
        }
        this.topJobs = Object.values(counts).sort((a, b) => b.count - a.count).slice(0, 5);
      });
  }

  loadStats(): void {
    this.statsLoading = true;
    this.http.get<AuditStats>(`${this.API}/api/audit/stats?callerRole=${this.callerRole}&${this.rangeParam}`, { headers: this.headers })
      .pipe(catchError(() => of(null)))
      .subscribe(s => { this.stats = s; this.statsLoading = false; });
  }

  loadLogs(): void {
    // A specific sub-filter overrides the grouped category; otherwise the
    // category key (JOBS/FLAGS/BLOCKS) or the plain filter is used.
    const effective = (this.currentSubFilters.length && this.activeSubFilter !== 'ALL')
      ? this.activeSubFilter
      : this.activeFilter;
    const eq = effective !== 'ALL' ? `&eventType=${effective}` : '';
    this.http.get<PageResponse<AuditLog>>(
      `${this.API}/api/audit/logs?page=${this.page}&size=${this.PAGE_SIZE}${eq}${this.rangeParam}&callerRole=${this.callerRole}`,
      { headers: this.headers }
    ).pipe(catchError(() => of({ content: [], totalElements: 0 } as any)))
     .subscribe(d => {
       this.logs = d.content || [];
       this.totalLogs = d.totalElements || 0;
       this.resolveNames(this.logs);
     });
  }

  private resolveNames(logs: AuditLog[]): void {
    for (const log of logs) {
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
      if (log.targetId && !this.targetNameCache[log.targetId]) {
        this.resolveTargetName(log);
      }
    }
  }

  private resolveTargetName(log: AuditLog): void {
    if (!log.targetId) return;
    const eventType = (log.eventType || '').toUpperCase();
    const producer  = (log.producer || '').toLowerCase();

    if (
      eventType === 'CANDIDATE_FLAGGED' ||
      eventType === 'CANDIDATE_UNFLAGGED' ||
      eventType === 'CANDIDATE_SIGNAL_DISMISSED'
    ) {
      this.http.get<{ email: string; firstName: string; lastName: string }>(
        `${this.API}/api/admin/internal/users/${log.targetId}/email`, { headers: this.headers }
      ).pipe(catchError(() => of(null))).subscribe(u => {
        if (u) this.targetNameCache[log.targetId!] =
          `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim() || u.email || log.targetId!.slice(0, 8);
      });
    } else if (
      eventType === 'APPLICATION_STATUS_UPDATE' ||
      eventType === 'APPLICATION_WITHDRAWN' ||
      producer.includes('application')
    ) {
      this.appService.getOne(log.targetId)
        .pipe(catchError(() => of(null)))
        .subscribe(a => {
          if (a) this.targetNameCache[log.targetId!] = a.jobTitle || `App #${log.targetId!.slice(0, 6)}`;
        });
    } else if (producer.includes('job')) {
      this.jobService.getJobById(log.targetId)
        .pipe(catchError(() => of(null)))
        .subscribe(j => {
          if (j) this.targetNameCache[log.targetId!] = j.title;
        });
    } else {
      this.http.get<{ email: string; firstName: string; lastName: string }>(
        `${this.API}/api/admin/internal/users/${log.targetId}/email`, { headers: this.headers }
      ).pipe(catchError(() => of(null))).subscribe(u => {
        if (u) this.targetNameCache[log.targetId!] =
          `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim() || u.email || log.targetId!.slice(0, 8);
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

  getDisplayReason(log: AuditLog): string | null {
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
    let raw: any = log.changes;
    if (typeof raw === 'string') { try { raw = JSON.parse(raw); } catch { return []; } }
    if (typeof raw === 'string') { try { raw = JSON.parse(raw); } catch { return []; } }
    if (!raw || typeof raw !== 'object') return [];
    return Object.entries(raw as Record<string, any>).map(([key, value]) => {
      if (value && typeof value === 'object' && ('old' in value || 'new' in value)) {
        return { key, oldVal: value['old'] != null ? String(value['old']) : null, newVal: value['new'] != null ? String(value['new']) : null, simple: null };
      }
      return { key, oldVal: null, newVal: null, simple: typeof value === 'object' ? JSON.stringify(value) : String(value ?? '') };
    });
  }

  unblockUser(userId: string, event: Event): void {
    event.stopPropagation();
    this.userService.setEnabled(userId, true)
      .then(() => {
        this.blockedUsers = this.blockedUsers.filter(u => u.id !== userId);
        const u = this.users.find(u => u.id === userId);
        if (u) u.enabled = true;
      })
      .catch(() => {});
  }

  scrollToAuditFilter(filter: string): void {
    this.setFilter(filter);
    setTimeout(() => {
      const el = document.getElementById('audit-log-section');
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 100);
  }

  setRange(key: string): void { this.activeRange = key; this.page = 0; this.loadStats(); this.loadLogs(); }
  setFilter(key: string): void {
    this.activeFilter = key;
    this.activeSubFilter = 'ALL';
    this.page = 0;
    this.loadLogs();
  }
  setSubFilter(key: string): void { this.activeSubFilter = key; this.page = 0; this.loadLogs(); }
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

  // ── Navigate from audit log entry ────────────────────────────────────────

  navigateFromLog(log: AuditLog): void {
    if (!log.targetId) return;
    const type = (log.eventType || '').toUpperCase();

    if (type === 'APPLICATION_STATUS_UPDATE' || type === 'APPLICATION_WITHDRAWN') {
      this.router.navigate(['/application', log.targetId]);
    } else if (
      type === 'CANDIDATE_FLAGGED' || type === 'CANDIDATE_UNFLAGGED' ||
      type === 'CANDIDATE_SIGNAL_DISMISSED' ||
      type === 'USER_BLOCK' || type === 'USER_UNBLOCK' ||
      type === 'ROLE_UPDATE'
    ) {
      this.router.navigate(['/user', log.targetId]);
    } else if (['JOB_UPDATED', 'JOB_CREATED', 'JOB_CLOSED', 'JOB_QUOTA_REACHED'].includes(type)) {
      this.router.navigate(['/jobs', log.targetId]);
    }
  }

  canNavigate(log: AuditLog): boolean {
    if (!log.targetId) return false;
    const type = (log.eventType || '').toUpperCase();
    return ['APPLICATION_STATUS_UPDATE', 'APPLICATION_WITHDRAWN',
            'CANDIDATE_FLAGGED', 'CANDIDATE_UNFLAGGED', 'CANDIDATE_SIGNAL_DISMISSED',
            'USER_BLOCK', 'USER_UNBLOCK',
            'JOB_UPDATED', 'JOB_CREATED', 'JOB_CLOSED', 'JOB_QUOTA_REACHED'].includes(type);
  }
}
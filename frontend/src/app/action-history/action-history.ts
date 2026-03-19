import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { RouterModule, ActivatedRoute } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { JobService } from '../services/job.service';
import { ApplicationService } from '../services/application.service';
import { AdminUserRow } from '../model/admin_users.type';
import { PageResponse } from '../model/page-response';

/* ── models (same as dashboard) ── */
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
  selector: 'app-action-history',
  imports: [CommonModule, RouterModule, FormsModule],
  templateUrl: './action-history.html',
  styleUrl: './action-history.css',
})
export class ActionHistory implements OnInit {
  private API = 'http://localhost:8888';

  /* ── user selector ── */
  admins: KcUser[] = [];
  selectedActorId  = '';
  selectedActor: KcUser | null = null;
  adminsLoading    = true;

  /* ── logs ── */
  logs: AuditLog[] = [];
  totalLogs = 0;
  page      = 0;
  readonly PAGE_SIZE = 10;
  loading   = false;

  /* ── filter ── */
  activeFilter = 'ALL';

  /* ── expand ── */
  expandedLogId: string | null = null;

  /* ── name caches ── */
  actorNameCache:  Record<string, string> = {};
  targetNameCache: Record<string, string> = {};

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

  constructor(
    private http: HttpClient,
    private route: ActivatedRoute,
    private jobService: JobService,
    private appService: ApplicationService,
  ) {}

  ngOnInit(): void {
    this.http.get<PageResponse<KcUser>>(
      `${this.API}/api/admin/users/paged?page=0&size=200`, { headers: this.headers }
    ).pipe(catchError(() => of(null)))
     .subscribe(data => {
       const all = data?.content || [];
       // ADMIN + RECRUITER only — no candidates
       this.admins = all.filter(u => {
         const roles = (u.roles ?? []).map(r => String(r).toUpperCase());
         const role  = (u.role  ?? '').toUpperCase();
         return roles.includes('ADMIN') || roles.includes('RECRUITER') ||
                role === 'ADMIN'        || role === 'RECRUITER';
       });
       // pre-populate name cache
       for (const u of this.admins) {
         this.actorNameCache[u.id] =
           `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim() || u.email || u.id.slice(0, 8);
       }
       this.adminsLoading = false;

       // deep-link ?actorId= from recruiter activity
       this.route.queryParams.subscribe(params => {
         if (params['actorId']) {
           this.selectedActorId = params['actorId'];
           this.onActorChange();
         }
       });
     });
  }

  private get headers(): HttpHeaders {
    const token = localStorage.getItem('access_token') || '';
    return new HttpHeaders({ Authorization: `Bearer ${token}` });
  }

  /* ── actor select ── */
  onActorChange(): void {
    if (!this.selectedActorId) { this.logs = []; this.totalLogs = 0; return; }
    this.selectedActor  = this.admins.find(a => a.id === this.selectedActorId) || null;
    this.activeFilter   = 'ALL';
    this.expandedLogId  = null;
    this.page           = 0;
    this.loadLogs();
  }

  /* ── filter ── */
  setFilter(key: string): void {
    this.activeFilter  = key;
    this.expandedLogId = null;
    this.page          = 0;
    this.loadLogs();
  }

  /* ── load logs ── */
  loadLogs(): void {
    if (!this.selectedActorId) return;
    this.loading = true;
    const eq = this.activeFilter !== 'ALL' ? `&eventType=${this.activeFilter}` : '';
    this.http.get<PageResponse<AuditLog>>(
      `${this.API}/api/audit/logs/actor/${this.selectedActorId}?page=${this.page}&size=${this.PAGE_SIZE}${eq}`,
      { headers: this.headers }
    ).pipe(catchError(() => of({ content: [], totalElements: 0 } as any)))
     .subscribe(d => {
       this.logs      = d.content || [];
       this.totalLogs = d.totalElements || 0;
       this.loading   = false;
       this.resolveNames(this.logs);
     });
  }

  /* ── name resolution (same as dashboard) ── */
  private resolveNames(logs: AuditLog[]): void {
    for (const log of logs) {
      if (log.actorUserId && log.actorUserId !== 'SYSTEM' && !this.actorNameCache[log.actorUserId]) {
        this.http.get<{ email: string; firstName: string; lastName: string }>(
          `${this.API}/api/admin/internal/users/${log.actorUserId}/email`, { headers: this.headers }
        ).pipe(catchError(() => of(null))).subscribe(u => {
          if (u) this.actorNameCache[log.actorUserId] =
            `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim() || u.email || log.actorUserId.slice(0, 8);
        });
      }
      if (log.targetId && !this.targetNameCache[log.targetId]) {
        this.resolveTargetName(log);
      }
    }
  }

  private resolveTargetName(log: AuditLog): void {
    if (!log.targetId) return;
    const producer = (log.producer || '').toLowerCase();
    if (producer.includes('job')) {
      this.jobService.getJobById(log.targetId).pipe(catchError(() => of(null)))
        .subscribe(j => { if (j) this.targetNameCache[log.targetId!] = j.title; });
    } else if (producer.includes('application')) {
      this.appService.getOne(log.targetId).pipe(catchError(() => of(null)))
        .subscribe(a => { if (a) this.targetNameCache[log.targetId!] = a.jobTitle || `App #${log.targetId!.slice(0, 6)}`; });
    } else {
      this.http.get<{ email: string; firstName: string; lastName: string }>(
        `${this.API}/api/admin/internal/users/${log.targetId}/email`, { headers: this.headers }
      ).pipe(catchError(() => of(null))).subscribe(u => {
        if (u) this.targetNameCache[log.targetId!] =
          `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim() || u.email || log.targetId!.slice(0, 8);
      });
    }
  }

  /* ── expand/collapse ── */
  toggleLog(log: AuditLog): void {
    this.expandedLogId = this.expandedLogId === log.eventId ? null : log.eventId;
  }
  isExpanded(log: AuditLog): boolean { return this.expandedLogId === log.eventId; }

  /* ── getters (same as dashboard) ── */
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
  getEventMeta(type: string): EventMeta {
    return this.EVENT_META[type] || { label: type, color: '#79a4e9', bg: 'rgba(121,164,233,0.12)' };
  }
  getUserRole(u: KcUser): string {
    const roles = (u.roles ?? []).map(r => String(r).toUpperCase());
    if (roles.includes('ADMIN'))     return 'ADMIN';
    if (roles.includes('RECRUITER')) return 'RECRUITER';
    return (u.role ?? '—').toUpperCase();
  }
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
  prevPage(): void { if (this.page > 0) { this.page--; this.loadLogs(); } }
  nextPage(): void { if (this.page + 1 < this.totalPages) { this.page++; this.loadLogs(); } }
  get totalPages(): number { return Math.ceil(this.totalLogs / this.PAGE_SIZE) || 1; }
  trackById(_: number, item: any): string { return item.eventId; }
}

import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import Keycloak from 'keycloak-js';
import { InterviewService, InterviewResponse } from '../services/interview-service';
import { NotificationSocketService } from '../services/notification-socket.service';

/**
 * A navbar widget that surfaces the single most-imminent interview for the
 * signed-in user - the recruiter who organised it (or was invited) and the
 * candidate it concerns. It stays out of the way until an interview is within
 * 2 hours, then counts down and escalates blue → amber → red as it nears.
 */
@Component({
  selector: 'app-imminent-interview',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './imminent-interview.html',
  styleUrl: './imminent-interview.css',
})
export class ImminentInterview implements OnInit, OnDestroy {
  private readonly keycloak = inject(Keycloak);
  private readonly interviewService = inject(InterviewService);
  private readonly router = inject(Router);
  private readonly socket = inject(NotificationSocketService);

  /** Only show the widget once an interview is this close. */
  private static readonly WINDOW_MS = 2 * 3_600_000;

  private interviews: InterviewResponse[] = [];
  now = Date.now();
  private ticker?: ReturnType<typeof setInterval>;
  private refetch?: ReturnType<typeof setInterval>;
  private changedSub?: Subscription;
  private wsSub?: Subscription;

  ngOnInit(): void {
    const id = this.keycloak.subject;
    if (!id) return;
    this.load(id);
    // Tick the countdown; periodically refetch so newly-scheduled interviews appear.
    this.ticker  = setInterval(() => { this.now = Date.now(); }, 30_000);
    this.refetch = setInterval(() => this.load(id), 5 * 60_000);
    // Refresh instantly when something in this same tab triggered a change.
    this.changedSub = this.interviewService.changed$.subscribe(() => this.load(id));
    // Refresh instantly when ANOTHER tab/user changed our interview set - the
    // backend pushes a thin ping on /topic/interviews.list.{userId} for every
    // schedule, cancel, reschedule, proposal-pick, delegation, invite that
    // affects us. No page refresh needed.
    this.wsSub = this.socket.interviewListChanged$.subscribe(() => this.load(id));
  }

  ngOnDestroy(): void {
    if (this.ticker)  clearInterval(this.ticker);
    if (this.refetch) clearInterval(this.refetch);
    this.changedSub?.unsubscribe();
    this.wsSub?.unsubscribe();
  }

  /** A candidate sees their own interviews; staff see ones they run or were invited to. */
  private load(id: string): void {
    const isCandidate = this.keycloak.hasRealmRole('CANDIDATE')
      && !this.keycloak.hasRealmRole('RECRUITER')
      && !this.keycloak.hasRealmRole('ADMIN')
      && !this.keycloak.hasRealmRole('SUPERADMIN');

    const source = isCandidate
      ? this.interviewService.getByCandidate(id)
      : this.interviewService.getAll();

    source.subscribe({
      next: list => {
        const all = list ?? [];
        this.interviews = isCandidate
          ? all
          : all.filter(iv => iv.recruiterId === id
              || (iv.invitedRecruiterIds ?? []).includes(id));
      },
      error: () => { this.interviews = []; },
    });
  }

  /** The most-imminent interview worth showing - a live one, else the soonest within the window. */
  get target(): InterviewResponse | null {
    let soonest: InterviewResponse | null = null;
    let soonestAt = Infinity;
    for (const iv of this.interviews) {
      if (iv.status === 'IN_PROGRESS') return iv;        // a live interview wins
      if (iv.status !== 'SCHEDULED') continue;
      const at = new Date(iv.scheduledAt).getTime();
      if (at - this.now > ImminentInterview.WINDOW_MS) continue;  // still too far off
      if (at < this.now - 90 * 60_000) continue;          // long over - ignore
      if (at < soonestAt) { soonestAt = at; soonest = iv; }
    }
    return soonest;
  }

  get visible(): boolean { return this.target !== null; }

  /** Urgency tier - drives the colour. */
  get tier(): 'soon' | 'near' | 'alert' {
    const iv = this.target;
    if (!iv) return 'soon';
    if (iv.status === 'IN_PROGRESS') return 'alert';
    const diff = new Date(iv.scheduledAt).getTime() - this.now;
    if (diff <= 5 * 60_000)  return 'alert';
    if (diff <= 60 * 60_000) return 'near';
    return 'soon';
  }

  get label(): string {
    const iv = this.target;
    if (!iv) return '';
    if (iv.status === 'IN_PROGRESS') return 'Interview live now';
    const diff = new Date(iv.scheduledAt).getTime() - this.now;
    if (diff <= 60_000) return 'Interview starting now';
    const mins = Math.floor(diff / 60_000);
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    const t = h > 0 ? h + 'h' + (m > 0 ? ' ' + m + 'm' : '') : m + ' min';
    return 'Interview in ' + t;
  }

  /** The job title, for the tooltip. */
  get jobTitle(): string { return this.target?.jobTitle || 'Interview'; }

  open(): void {
    const iv = this.target;
    if (iv) this.router.navigate(['/interview', iv.id, 'room']);
  }
}

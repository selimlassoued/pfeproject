import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import Keycloak from 'keycloak-js';
import { InterviewService, InterviewResponse } from '../services/interview-service';
import { GoogleCalendarService } from '../services/google-calendar-service';

interface CalDay {
  date: Date;
  inMonth: boolean;
  isToday: boolean;
  isPast: boolean;
  weekend: boolean;
  interviews: InterviewResponse[];
}

@Component({
  selector: 'app-interview-calendar',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './interview-calendar.html',
  styleUrl: './interview-calendar.css',
})
export class InterviewCalendar implements OnInit, OnDestroy {
  private readonly keycloak = inject(Keycloak);
  private readonly interviewService = inject(InterviewService);
  private readonly google = inject(GoogleCalendarService);
  private readonly router = inject(Router);

  loading = true;
  error = '';

  /** Every team interview, as loaded from the backend. */
  private allInterviews: InterviewResponse[] = [];
  /** The subset currently shown — driven by {@link scope}. */
  interviews: InterviewResponse[] = [];

  /** 'team' = everyone's interviews, 'mine' = only this recruiter's. */
  scope: 'team' | 'mine' = 'team';
  /** Keycloak id of the signed-in recruiter — used to tell "mine" apart. */
  myId = '';

  /** Whether this recruiter has linked their Google Calendar. */
  googleConnected = false;

  /** Current time, refreshed every 30s so the live countdowns tick. */
  now = Date.now();
  private ticker?: ReturnType<typeof setInterval>;

  viewDate = new Date();
  weeks: CalDay[][] = [];
  selectedDay: CalDay | null = null;

  readonly weekdays = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  private readonly monthNames = ['January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December'];

  ngOnInit(): void {
    const recruiterId = this.keycloak.subject ?? '';
    if (!recruiterId) { this.error = 'Not authenticated.'; this.loading = false; return; }
    this.myId = recruiterId;
    this.ticker = setInterval(() => { this.now = Date.now(); }, 30_000);

    this.interviewService.getAll().subscribe({
      next: list => {
        this.allInterviews = list ?? [];
        this.applyScope();
        this.loading = false;
      },
      error: () => { this.error = 'Could not load interviews.'; this.loading = false; },
    });

    this.google.status(recruiterId).subscribe({
      next: r => this.googleConnected = r.connected,
      error: () => { /* sync status is non-critical — leave it disconnected */ },
    });
  }

  ngOnDestroy(): void {
    if (this.ticker) clearInterval(this.ticker);
  }

  /** Switch between the whole team's interviews and just this recruiter's. */
  setScope(scope: 'team' | 'mine'): void {
    if (this.scope === scope) return;
    this.scope = scope;
    this.applyScope();
  }

  /** Recompute the visible interview set and rebuild the grid. */
  private applyScope(): void {
    this.interviews = this.scope === 'mine'
      ? this.allInterviews.filter(iv => iv.recruiterId === this.myId)
      : this.allInterviews;
    this.buildGrid();
  }

  /** Google Calendar sync is a recruiter tool — admins/superadmins don't schedule interviews. */
  get isRecruiter(): boolean {
    return this.keycloak.hasRealmRole('RECRUITER');
  }

  /** Send the recruiter to Google's consent screen. */
  connectGoogle(): void {
    window.location.href = this.google.authUrl();
  }

  disconnectGoogle(): void {
    const recruiterId = this.keycloak.subject ?? '';
    if (!recruiterId) return;
    this.google.disconnect(recruiterId).subscribe({
      next: () => this.googleConnected = false,
      error: () => { /* ignore — user can retry */ },
    });
  }

  get monthLabel(): string {
    return `${this.monthNames[this.viewDate.getMonth()]} ${this.viewDate.getFullYear()}`;
  }

  /** Scheduled interviews still in the future. */
  get upcomingCount(): number {
    const now = Date.now();
    return this.interviews.filter(iv =>
      iv.status === 'SCHEDULED' && new Date(iv.scheduledAt).getTime() >= now).length;
  }

  private buildGrid(): void {
    const year = this.viewDate.getFullYear();
    const month = this.viewDate.getMonth();
    const first = new Date(year, month, 1);
    const startOffset = (first.getDay() + 6) % 7;   // Monday-first grid
    const today = new Date(); today.setHours(0, 0, 0, 0);

    const weeks: CalDay[][] = [];
    const cursor = new Date(year, month, 1 - startOffset);
    for (let w = 0; w < 6; w++) {
      const week: CalDay[] = [];
      for (let d = 0; d < 7; d++) {
        const date = new Date(cursor);
        week.push({
          date,
          inMonth: date.getMonth() === month,
          isToday: date.getTime() === today.getTime(),
          isPast: date.getTime() < today.getTime(),
          weekend: date.getDay() === 0 || date.getDay() === 6,
          interviews: this.interviewsOn(date),
        });
        cursor.setDate(cursor.getDate() + 1);
      }
      weeks.push(week);
    }
    this.weeks = weeks;

    // Re-point the selected day at the freshly built cell, if it's still visible.
    if (this.selectedDay) {
      const t = this.selectedDay.date.getTime();
      this.selectedDay = weeks.flat().find(d => d.date.getTime() === t) ?? null;
    }
  }

  private interviewsOn(date: Date): InterviewResponse[] {
    return this.interviews
      .filter(iv => {
        const d = new Date(iv.scheduledAt);
        return d.getFullYear() === date.getFullYear()
            && d.getMonth() === date.getMonth()
            && d.getDate() === date.getDate();
      })
      .sort((a, b) => +new Date(a.scheduledAt) - +new Date(b.scheduledAt));
  }

  prevMonth(): void {
    this.viewDate = new Date(this.viewDate.getFullYear(), this.viewDate.getMonth() - 1, 1);
    this.buildGrid();
  }
  nextMonth(): void {
    this.viewDate = new Date(this.viewDate.getFullYear(), this.viewDate.getMonth() + 1, 1);
    this.buildGrid();
  }
  goToday(): void {
    this.viewDate = new Date();
    this.buildGrid();
    this.selectedDay = this.weeks.flat().find(d => d.isToday) ?? null;
  }

  selectDay(day: CalDay): void {
    this.selectedDay =
      this.selectedDay?.date.getTime() === day.date.getTime() ? null : day;
  }
  clearDay(): void { this.selectedDay = null; }

  /** Interviews from now onward, soonest first — drives the side agenda. */
  get upcomingList(): InterviewResponse[] {
    const cutoff = Date.now() - 3 * 3600_000;   // keep ones from the last 3h
    return [...this.interviews]
      .filter(iv => new Date(iv.scheduledAt).getTime() >= cutoff)
      .sort((a, b) => +new Date(a.scheduledAt) - +new Date(b.scheduledAt))
      .slice(0, 12);
  }

  /** What the side panel shows: a clicked day's interviews, else the agenda. */
  get sideList(): InterviewResponse[] {
    return this.selectedDay ? this.selectedDay.interviews : this.upcomingList;
  }
  get sideTitle(): string {
    return this.selectedDay ? this.dayTitle(this.selectedDay.date) : 'Upcoming interviews';
  }

  /** "Today" / "Tomorrow" / "Mon 19 May" — relative day label. */
  relativeDay(iso: string): string {
    const d = new Date(iso); d.setHours(0, 0, 0, 0);
    const today = new Date(); today.setHours(0, 0, 0, 0);
    const diff = Math.round((d.getTime() - today.getTime()) / 86_400_000);
    if (diff === 0) return 'Today';
    if (diff === 1) return 'Tomorrow';
    if (diff === -1) return 'Yesterday';
    return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
  }

  statusClass(status: string): string { return 'st-' + status.toLowerCase(); }

  time(iso: string): string {
    return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  }
  dayTitle(d: Date): string {
    return d.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' });
  }
  candidateName(iv: InterviewResponse): string {
    return (iv.candidateEmail || '').split('@')[0] || 'Candidate';
  }
  /** Who scheduled this interview — "you" for the signed-in recruiter. */
  recruiterName(iv: InterviewResponse): string {
    if (iv.recruiterId === this.myId) return 'you';
    return (iv.recruiterEmail || '').split('@')[0] || 'a recruiter';
  }
  isMine(iv: InterviewResponse): boolean {
    return iv.recruiterId === this.myId;
  }
  /** A recruiter may enter the room only for their own or an invited interview. */
  canJoin(iv: InterviewResponse): boolean {
    return this.keycloak.hasRealmRole('ADMIN')
      || this.keycloak.hasRealmRole('SUPERADMIN')
      || iv.recruiterId === this.myId
      || (iv.invitedRecruiterIds ?? []).includes(this.myId);
  }

  /** Live countdown label — "" when the interview is too far off or finished. */
  countdownLabel(iv: InterviewResponse): string {
    if (iv.status === 'IN_PROGRESS') return 'Live now';
    if (iv.status !== 'SCHEDULED') return '';
    const diff = new Date(iv.scheduledAt).getTime() - this.now;
    if (diff <= 60_000) return 'Starting now';
    if (diff > 24 * 3_600_000) return '';          // the date label is enough
    const mins = Math.floor(diff / 60_000);
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    return h > 0
      ? 'in ' + h + 'h' + (m > 0 ? ' ' + m + 'm' : '')
      : 'in ' + m + ' min';
  }

  /** Urgency class for the countdown chip — drives its colour. */
  countdownClass(iv: InterviewResponse): string {
    if (iv.status === 'IN_PROGRESS') return 'cd-live';
    if (iv.status !== 'SCHEDULED') return '';
    const diff = new Date(iv.scheduledAt).getTime() - this.now;
    if (diff <= 60_000) return 'cd-live';
    if (diff > 24 * 3_600_000) return '';
    if (diff <= 60 * 60_000) return 'cd-imminent';
    return 'cd-soon';
  }

  /** Click anywhere on an interview card (except its buttons) → open the application. */
  openApplication(iv: InterviewResponse): void {
    this.router.navigate(['/application', iv.applicationId]);
  }

  join(iv: InterviewResponse): void {
    if (!this.canJoin(iv)) return;
    this.router.navigate(['/interview', iv.id, 'room']);
  }
  viewResult(iv: InterviewResponse): void { this.router.navigate(['/interview', iv.id, 'result']); }
}

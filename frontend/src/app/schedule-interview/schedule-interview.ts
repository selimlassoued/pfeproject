import { Component, Input, Output, EventEmitter, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { InterviewService, InterviewResponse } from '../services/interview-service';

/** A bookable hour, plus why it might not be bookable. */
interface Slot {
  time: string;   // "14:00"
  state: 'free' | 'past' | 'recruiter' | 'candidate' | 'both';
}

@Component({
  selector: 'app-schedule-interview',
  imports: [CommonModule, FormsModule],
  templateUrl: './schedule-interview.html',
  styleUrl: './schedule-interview.css',
})
export class ScheduleInterview implements OnInit {
  @Input() applicationId!: string;
  @Input() jobId!: string;
  @Input() jobTitle!: string;
  @Input() candidateId!: string;
  @Input() candidateEmail!: string;
  @Input() recruiterId!: string;
  @Input() recruiterEmail!: string;

  @Output() scheduled = new EventEmitter<InterviewResponse>();

  private readonly interviewService = inject(InterviewService);

  /** Two interviews must be at least this far apart — matches the backend rule. */
  private static readonly GAP_MS = 60 * 60 * 1000;
  /** Bookable hours of the day. */
  readonly hours = [8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18];

  loading = false;
  busyLoading = true;
  error = '';

  /** Start times (epoch ms) of the recruiter's / candidate's active interviews. */
  private busyRecruiter: number[] = [];
  private busyCandidate: number[] = [];

  minDate = '';
  selectedDate = '';
  selectedSlot: string | null = null;
  /** Quarter-hours within an hour — interviews start at :00 / :15 / :30 / :45 only. */
  private readonly minutesInHour = [0, 15, 30, 45];

  /**
   * Dev/test escape hatch: when on, the scheduler ignores the weekend block and
   * the "past slot" filter so you can pick anything — including a time that has
   * already passed (the room then opens immediately). Keep OFF for real users.
   */
  testMode = false;

  ngOnInit(): void {
    const today = new Date();
    this.minDate = this.toDateStr(today);
    this.selectedDate = this.minDate;

    // Load both parties' agendas so taken slots can be greyed out up front.
    forkJoin({
      recruiter: this.interviewService.getByRecruiter(this.recruiterId),
      candidate: this.interviewService.getByCandidate(this.candidateId),
    }).subscribe({
      next: ({ recruiter, candidate }) => {
        const active = (iv: InterviewResponse) =>
          iv.status === 'SCHEDULED' || iv.status === 'IN_PROGRESS';
        this.busyRecruiter = recruiter.filter(active)
          .map(iv => new Date(iv.scheduledAt).getTime());
        this.busyCandidate = candidate.filter(active)
          .map(iv => new Date(iv.scheduledAt).getTime());
        this.busyLoading = false;
      },
      error: () => { this.busyLoading = false; },   // backend still enforces it
    });
  }

  private toDateStr(d: Date): string {
    const m = `${d.getMonth() + 1}`.padStart(2, '0');
    const day = `${d.getDate()}`.padStart(2, '0');
    return `${d.getFullYear()}-${m}-${day}`;
  }

  /** All bookable quarter-hour slots for the selected day, grouped by hour. */
  get slotsByHour(): { hour: number; quarters: Slot[] }[] {
    // testMode bypasses the weekend block (otherwise the grid is empty on Sat/Sun).
    if (!this.selectedDate) return [];
    if (this.isWeekend && !this.testMode) return [];
    const now = Date.now();
    const rows = this.hours.map(h => {
      const quarters: Slot[] = this.minutesInHour.map(m => {
        const hh = String(h).padStart(2, '0');
        const mm = String(m).padStart(2, '0');
        const dt = new Date(`${this.selectedDate}T${hh}:${mm}:00`).getTime();
        let state: Slot['state'] = 'free';
        // In test mode, past slots stay 'free' so they can be picked — picking
        // a past time means the interview room opens immediately on entry.
        if (dt < now && !this.testMode) {
          state = 'past';
        } else {
          const r = this.busyRecruiter.some(t => Math.abs(t - dt) < ScheduleInterview.GAP_MS);
          const c = this.busyCandidate.some(t => Math.abs(t - dt) < ScheduleInterview.GAP_MS);
          if (r && c) state = 'both';
          else if (r) state = 'recruiter';
          else if (c) state = 'candidate';
        }
        return { time: `${hh}:${mm}`, state };
      });
      return { hour: h, quarters };
    });
    // Drop hours where every quarter has already passed — keeps the grid compact.
    // In test mode we leave them, since past slots are now legitimately pickable.
    return this.testMode
      ? rows
      : rows.filter(r => r.quarters.some(q => q.state !== 'past'));
  }

  /** Interviews are weekdays only — Saturday and Sunday are off-limits. */
  get isWeekend(): boolean {
    if (!this.selectedDate) return false;
    const day = new Date(this.selectedDate).getDay();
    return day === 0 || day === 6;
  }

  get dateError(): string {
    if (!this.selectedDate) return '';
    if (this.isWeekend && !this.testMode) return "Interviews can't be scheduled on weekends — pick a weekday.";
    return '';
  }

  /**
   * Test-mode shortcut — schedule an interview 30 s from now. The auto-start
   * countdown then runs out almost immediately, dropping straight into Jitsi
   * without having to pick a date / slot manually.
   */
  scheduleNow(): void {
    if (this.loading) return;
    this.loading = true;
    this.error = '';
    const at = new Date(Date.now() + 30_000);
    const pad = (n: number) => String(n).padStart(2, '0');
    const scheduledAt =
      `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}` +
      `T${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())}`;
    this.interviewService.schedule({
      applicationId: this.applicationId,
      jobId: this.jobId,
      jobTitle: this.jobTitle,
      recruiterId: this.recruiterId,
      candidateId: this.candidateId,
      candidateEmail: this.candidateEmail,
      recruiterEmail: this.recruiterEmail,
      scheduledAt,
      recordingConsent: false,
    }).subscribe({
      next: (interview) => { this.loading = false; this.scheduled.emit(interview); },
      error: (err) => {
        this.loading = false;
        this.error = err?.error?.message
          || 'Failed to schedule interview. Please try again.';
        console.error(err);
      },
    });
  }

  pickSlot(slot: Slot): void {
    if (slot.state !== 'free') return;
    this.selectedSlot = slot.time;
  }

  /** A new day invalidates the slot chosen on the previous one. */
  onDateChange(): void {
    this.selectedSlot = null;
  }

  slotTitle(slot: Slot): string {
    switch (slot.state) {
      case 'past':      return 'This time has already passed';
      case 'recruiter': return 'You already have an interview around this time';
      case 'candidate': return 'The candidate is busy around this time';
      case 'both':      return 'You and the candidate are both busy around this time';
      default:          return 'Available';
    }
  }

  submit(): void {
    if (!this.selectedDate || !this.selectedSlot || this.loading) return;
    this.loading = true;
    this.error = '';

    this.interviewService.schedule({
      applicationId: this.applicationId,
      jobId: this.jobId,
      jobTitle: this.jobTitle,
      recruiterId: this.recruiterId,
      candidateId: this.candidateId,
      candidateEmail: this.candidateEmail,
      recruiterEmail: this.recruiterEmail,
      scheduledAt: `${this.selectedDate}T${this.selectedSlot}:00`,
      recordingConsent: false,
    }).subscribe({
      next: (interview) => {
        this.loading = false;
        this.scheduled.emit(interview);
      },
      error: (err) => {
        this.loading = false;
        // Surface the backend's reason (e.g. a scheduling clash) when there is one.
        this.error = err?.error?.message
          || 'Failed to schedule interview. Please try again.';
        console.error(err);
      },
    });
  }
}

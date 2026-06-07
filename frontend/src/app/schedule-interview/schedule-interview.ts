import { Component, Input, Output, EventEmitter, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { InterviewService, InterviewResponse, ProposalResponse } from '../services/interview-service';
import { normalizeHttpError } from '../utils/http-error';

/** A bookable hour, plus why it might not be bookable. */
interface Slot {
  time: string;   // "14:00"
  state: 'free' | 'past' | 'recruiter' | 'candidate' | 'both';
}

/** Selected slot accumulated during proposal building (date + HH:mm). */
interface PickedSlot {
  date: string;     // "2026-05-28"
  time: string;     // "14:30"
  iso: string;      // "2026-05-28T14:30:00" - what the backend stores
  label: string;    // user-facing
}

type SchedulerMode = 'propose' | 'direct';

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

  /** Emitted when a real interview is scheduled directly (skipping the proposal flow). */
  @Output() scheduled = new EventEmitter<InterviewResponse>();
  /** Emitted when a proposal has been sent (candidate will pick a slot). */
  @Output() proposed = new EventEmitter<ProposalResponse>();

  private readonly interviewService = inject(InterviewService);

  /** Two interviews must be at least this far apart - matches the backend rule. */
  private static readonly GAP_MS = 60 * 60 * 1000;
  /** Bookable hours of the day. */
  readonly hours = [8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18];
  /** Quarter-hours within an hour - interviews start at :00 / :15 / :30 / :45 only. */
  private readonly minutesInHour = [0, 15, 30, 45];

  /** Default mode is propose - candidate picks from 2-4 offered slots. Direct
   *  schedule stays available for when both sides already agreed on a time. */
  mode: SchedulerMode = 'propose';

  loading = false;
  busyLoading = true;
  error = '';
  /** Backend per-field validation messages keyed by DTO field name. */
  fieldErrors: Record<string, string> = {};

  private busyRecruiter: number[] = [];
  private busyCandidate: number[] = [];

  minDate = '';
  selectedDate = '';

  /** Direct-mode: single picked slot. */
  selectedSlot: string | null = null;

  /** Proposal-mode: accumulating list of picked slots (across days). Capped at 4. */
  proposedSlots: PickedSlot[] = [];
  static readonly MIN_PROPOSED = 1;
  static readonly MAX_PROPOSED = 4;

  /** Proposal-mode: deadline by when the candidate must pick. Default: 48h. */
  deadline = '';
  /** Proposal-mode: optional note shown to the candidate. */
  message = '';

  testMode = false;

  ngOnInit(): void {
    const today = new Date();
    this.minDate = this.toDateStr(today);
    this.selectedDate = this.minDate;
    this.deadline = this.defaultDeadline();

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
      error: () => { this.busyLoading = false; },
    });
  }

  // ── Helpers ─────────────────────────────────────────────────────────────
  private toDateStr(d: Date): string {
    const m = `${d.getMonth() + 1}`.padStart(2, '0');
    const day = `${d.getDate()}`.padStart(2, '0');
    return `${d.getFullYear()}-${m}-${day}`;
  }

  private defaultDeadline(): string {
    // 48 h from now, rounded down to the start of the hour - matches the
    // <input type="datetime-local"> format (no seconds, no Z).
    const d = new Date(Date.now() + 48 * 60 * 60 * 1000);
    d.setMinutes(0, 0, 0);
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
           `T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  /** All bookable quarter-hour slots for the selected day, grouped by hour. */
  get slotsByHour(): { hour: number; quarters: Slot[] }[] {
    if (!this.selectedDate) return [];
    if (this.isWeekend && !this.testMode) return [];
    const now = Date.now();
    const rows = this.hours.map(h => {
      const quarters: Slot[] = this.minutesInHour.map(m => {
        const hh = String(h).padStart(2, '0');
        const mm = String(m).padStart(2, '0');
        const dt = new Date(`${this.selectedDate}T${hh}:${mm}:00`).getTime();
        let state: Slot['state'] = 'free';
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
    return this.testMode
      ? rows
      : rows.filter(r => r.quarters.some(q => q.state !== 'past'));
  }

  get isWeekend(): boolean {
    if (!this.selectedDate) return false;
    const day = new Date(this.selectedDate).getDay();
    return day === 0 || day === 6;
  }

  get dateError(): string {
    if (!this.selectedDate) return '';
    if (this.isWeekend && !this.testMode) return "Interviews can't be scheduled on weekends - pick a weekday.";
    return '';
  }

  // ── Mode toggle ─────────────────────────────────────────────────────────
  setMode(m: SchedulerMode): void {
    this.mode = m;
    this.error = '';
    // Don't drop the user's picks just because they peeked at the other mode -
    // proposedSlots and selectedSlot are kept independently.
  }

  // ── Slot picking ────────────────────────────────────────────────────────
  pickSlot(slot: Slot): void {
    if (slot.state !== 'free') return;
    if (this.mode === 'direct') {
      this.selectedSlot = slot.time;
      return;
    }
    // Propose mode - toggle in/out of proposedSlots
    const iso = `${this.selectedDate}T${slot.time}:00`;
    const existing = this.proposedSlots.findIndex(p => p.iso === iso);
    if (existing >= 0) {
      this.proposedSlots.splice(existing, 1);
      return;
    }
    if (this.proposedSlots.length >= ScheduleInterview.MAX_PROPOSED) {
      this.error = `You can offer at most ${ScheduleInterview.MAX_PROPOSED} slots.`;
      setTimeout(() => { this.error = ''; }, 3000);
      return;
    }
    this.proposedSlots.push({
      date: this.selectedDate,
      time: slot.time,
      iso,
      label: this.formatSlotLabel(this.selectedDate, slot.time),
    });
    // Keep them chronologically ordered so the candidate sees them in order.
    this.proposedSlots.sort((a, b) => a.iso.localeCompare(b.iso));
  }

  removeProposedSlot(iso: string): void {
    this.proposedSlots = this.proposedSlots.filter(s => s.iso !== iso);
  }

  isSlotPicked(time: string): boolean {
    if (this.mode === 'direct') return this.selectedSlot === time;
    const iso = `${this.selectedDate}T${time}:00`;
    return this.proposedSlots.some(p => p.iso === iso);
  }

  private formatSlotLabel(date: string, time: string): string {
    const d = new Date(`${date}T${time}:00`);
    const day = d.toLocaleDateString(undefined, {
      weekday: 'short', month: 'short', day: 'numeric',
    });
    return `${day} · ${time}`;
  }

  onDateChange(): void {
    // In direct mode, switching day invalidates the single-slot selection.
    // In propose mode, accumulated picks across days stay intact.
    if (this.mode === 'direct') this.selectedSlot = null;
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

  // ── Submit (direct) ─────────────────────────────────────────────────────
  submit(): void {
    if (!this.selectedDate || !this.selectedSlot || this.loading) return;
    this.loading = true;
    this.error = '';
    this.fieldErrors = {};

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
        const httpError = normalizeHttpError(err);
        this.error = httpError.message || 'Failed to schedule interview. Please try again.';
        this.fieldErrors = httpError.fieldErrors;
        console.error(err);
      },
    });
  }

  // ── Submit (proposal) ───────────────────────────────────────────────────
  get canSendProposal(): boolean {
    return this.proposedSlots.length >= ScheduleInterview.MIN_PROPOSED
      && this.proposedSlots.length <= ScheduleInterview.MAX_PROPOSED
      && !!this.deadline
      && !this.loading;
  }

  sendProposal(): void {
    if (!this.canSendProposal) return;
    this.loading = true;
    this.error = '';
    this.fieldErrors = {};

    this.interviewService.createProposal({
      applicationId: this.applicationId,
      jobId: this.jobId,
      jobTitle: this.jobTitle,
      recruiterId: this.recruiterId,
      candidateId: this.candidateId,
      candidateEmail: this.candidateEmail,
      recruiterEmail: this.recruiterEmail,
      proposedSlots: this.proposedSlots.map(s => s.iso),
      // datetime-local input gives us "YYYY-MM-DDTHH:mm" - add :00 so the
      // backend's LocalDateTime parser accepts it.
      deadline: this.deadline.length === 16 ? `${this.deadline}:00` : this.deadline,
      message: this.message?.trim() || undefined,
    }).subscribe({
      next: (proposal) => {
        this.loading = false;
        this.proposed.emit(proposal);
      },
      error: (err) => {
        this.loading = false;
        const httpError = normalizeHttpError(err);
        this.error = httpError.message || 'Failed to send proposal. Please try again.';
        this.fieldErrors = httpError.fieldErrors;
        console.error(err);
      },
    });
  }

  // ── Test-mode shortcut (direct only) ────────────────────────────────────
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
        this.error = normalizeHttpError(err).message
          || 'Failed to schedule interview. Please try again.';
        console.error(err);
      },
    });
  }
}

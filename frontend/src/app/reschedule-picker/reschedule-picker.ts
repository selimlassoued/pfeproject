import { Component, EventEmitter, Input, OnInit, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { normalizeHttpError } from '../utils/http-error';
import {
  CreateReschedRequest,
  InterviewService,
  InterviewResponse,
  ReschedRequestResponse,
} from '../services/interview-service';

interface Slot {
  time: string;   // "HH:mm"
  state: 'free' | 'past' | 'recruiter' | 'candidate' | 'both';
}
interface PickedSlot {
  date: string;
  time: string;
  iso: string;
  label: string;
}

/**
 * Compact slot picker for rescheduling an existing interview. Mirrors the
 * propose-slots flow inside ScheduleInterview but submits to the reschedule
 * endpoint and returns the resulting ReschedRequestResponse.
 */
@Component({
  selector: 'app-reschedule-picker',
  imports: [CommonModule, FormsModule],
  templateUrl: './reschedule-picker.html',
  styleUrl: './reschedule-picker.css',
})
export class ReschedulePicker implements OnInit {
  @Input({ required: true }) interviewId!: string;
  @Input({ required: true }) recruiterId!: string;
  @Input({ required: true }) candidateId!: string;

  @Output() proposed = new EventEmitter<ReschedRequestResponse>();
  @Output() cancelled = new EventEmitter<void>();

  private static readonly GAP_MS = 60 * 60 * 1000;
  readonly hours = [8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18];
  private readonly minutesInHour = [0, 15, 30, 45];
  static readonly MIN_PROPOSED = 1;
  static readonly MAX_PROPOSED = 4;

  private interviewService = inject(InterviewService);

  loading = false;
  busyLoading = true;
  error = '';
  /** Per-field server validation messages keyed by DTO field name. */
  fieldErrors: Record<string, string> = {};

  private busyRecruiter: number[] = [];
  private busyCandidate: number[] = [];

  minDate = '';
  selectedDate = '';
  proposedSlots: PickedSlot[] = [];
  deadline = '';
  message = '';

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
          (iv.status === 'SCHEDULED' || iv.status === 'IN_PROGRESS')
          && iv.id !== this.interviewId; // ignore the one we're rescheduling
        this.busyRecruiter = recruiter.filter(active).map(iv => new Date(iv.scheduledAt).getTime());
        this.busyCandidate = candidate.filter(active).map(iv => new Date(iv.scheduledAt).getTime());
        this.busyLoading = false;
      },
      error: () => { this.busyLoading = false; },
    });
  }

  // ── Slot grid ───────────────────────────────────────────────────────────
  get slotsByHour(): { hour: number; quarters: Slot[] }[] {
    if (!this.selectedDate) return [];
    if (this.isWeekend) return [];
    const now = Date.now();
    const rows = this.hours.map(h => {
      const quarters: Slot[] = this.minutesInHour.map(m => {
        const hh = String(h).padStart(2, '0');
        const mm = String(m).padStart(2, '0');
        const dt = new Date(`${this.selectedDate}T${hh}:${mm}:00`).getTime();
        let state: Slot['state'] = 'free';
        if (dt < now) {
          state = 'past';
        } else {
          const r = this.busyRecruiter.some(t => Math.abs(t - dt) < ReschedulePicker.GAP_MS);
          const c = this.busyCandidate.some(t => Math.abs(t - dt) < ReschedulePicker.GAP_MS);
          if (r && c) state = 'both';
          else if (r) state = 'recruiter';
          else if (c) state = 'candidate';
        }
        return { time: `${hh}:${mm}`, state };
      });
      return { hour: h, quarters };
    });
    return rows.filter(r => r.quarters.some(q => q.state !== 'past'));
  }

  get isWeekend(): boolean {
    if (!this.selectedDate) return false;
    const day = new Date(this.selectedDate).getDay();
    return day === 0 || day === 6;
  }

  get dateError(): string {
    if (!this.selectedDate) return '';
    if (this.isWeekend) return "Interviews can't be scheduled on weekends - pick a weekday.";
    return '';
  }

  pickSlot(slot: Slot): void {
    if (slot.state !== 'free') return;
    const iso = `${this.selectedDate}T${slot.time}:00`;
    const existing = this.proposedSlots.findIndex(p => p.iso === iso);
    if (existing >= 0) { this.proposedSlots.splice(existing, 1); return; }
    if (this.proposedSlots.length >= ReschedulePicker.MAX_PROPOSED) {
      this.error = `You can offer at most ${ReschedulePicker.MAX_PROPOSED} slots.`;
      setTimeout(() => { this.error = ''; }, 3000);
      return;
    }
    this.proposedSlots.push({
      date: this.selectedDate,
      time: slot.time,
      iso,
      label: this.formatSlotLabel(this.selectedDate, slot.time),
    });
    this.proposedSlots.sort((a, b) => a.iso.localeCompare(b.iso));
  }

  removeProposedSlot(iso: string): void {
    this.proposedSlots = this.proposedSlots.filter(s => s.iso !== iso);
  }

  isSlotPicked(time: string): boolean {
    const iso = `${this.selectedDate}T${time}:00`;
    return this.proposedSlots.some(p => p.iso === iso);
  }

  slotTitle(slot: Slot): string {
    switch (slot.state) {
      case 'past':      return 'This time has already passed';
      case 'recruiter': return 'Recruiter is busy around this time';
      case 'candidate': return 'Candidate is busy around this time';
      case 'both':      return 'Both parties are busy around this time';
      default:          return 'Available';
    }
  }

  // ── Submit ──────────────────────────────────────────────────────────────
  get canSubmit(): boolean {
    return this.proposedSlots.length >= ReschedulePicker.MIN_PROPOSED
      && this.proposedSlots.length <= ReschedulePicker.MAX_PROPOSED
      && !!this.deadline
      && !this.loading;
  }

  submit(): void {
    if (!this.canSubmit) return;
    this.loading = true;
    this.error = '';
    this.fieldErrors = {};
    const req: CreateReschedRequest = {
      proposedSlots: this.proposedSlots.map(s => s.iso),
      deadline: this.deadline.length === 16 ? `${this.deadline}:00` : this.deadline,
      message: this.message?.trim() || undefined,
    };
    this.interviewService.proposeReschedule(this.interviewId, req).subscribe({
      next: (r) => { this.loading = false; this.proposed.emit(r); },
      error: (err) => {
        this.loading = false;
        const httpError = normalizeHttpError(err);
        this.error = httpError.message || 'Failed to propose new times. Please try again.';
        this.fieldErrors = httpError.fieldErrors;
        console.error(err);
      },
    });
  }

  cancel(): void { this.cancelled.emit(); }

  // ── Helpers ─────────────────────────────────────────────────────────────
  private toDateStr(d: Date): string {
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  }
  private defaultDeadline(): string {
    const d = new Date(Date.now() + 24 * 60 * 60 * 1000);
    d.setMinutes(0, 0, 0);
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
           `T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }
  private formatSlotLabel(date: string, time: string): string {
    const d = new Date(`${date}T${time}:00`);
    const day = d.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
    return `${day} · ${time}`;
  }
}

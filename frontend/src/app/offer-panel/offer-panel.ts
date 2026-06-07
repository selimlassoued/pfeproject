import { Component, EventEmitter, Input, OnDestroy, OnInit, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import Swal from 'sweetalert2';
import { Subscription } from 'rxjs';
import {
  ContractType,
  CreateOfferRequest,
  CreateRevisionRequest,
  OfferDto,
  OfferService,
} from '../services/offer.service';
import { NotificationSocketService } from '../services/notification-socket.service';
import { normalizeHttpError } from '../utils/http-error';

type Mode = 'RECRUITER' | 'CANDIDATE';

@Component({
  selector: 'app-offer-panel',
  imports: [CommonModule, FormsModule],
  templateUrl: './offer-panel.html',
  styleUrl: './offer-panel.css',
})
export class OfferPanel implements OnInit, OnDestroy {
  @Input({ required: true }) applicationId!: string;
  @Input({ required: true }) mode!: Mode;
  /** Disable the create-offer form when the application status doesn't allow it. */
  @Input() canCreateOffer = true;

  /** Fires whenever the offer transitions - parent should refresh app status. */
  @Output() changed = new EventEmitter<OfferDto>();

  private offerService = inject(OfferService);
  private socket = inject(NotificationSocketService);
  private wsSub?: Subscription;

  offer: OfferDto | null = null;
  loading = true;
  saving = false;
  error: string | null = null;
  /** Server-side validation errors keyed by field name. Populated from
   *  the backend's GlobalExceptionHandler `details` map so the template
   *  can render each message next to the input it concerns. */
  fieldErrors: Record<string, string> = {};

  readonly contractTypes: ContractType[] = ['CDI', 'CDD', 'INTERNSHIP', 'ALTERNANCE', 'FREELANCE'];

  // ── Create-offer form (recruiter only) ──────────────────────────────────
  showCreateForm = false;
  form = {
    salary: '' as number | '',
    currency: 'TND',
    startDate: '',
    contractType: 'CDI' as ContractType,
    message: '',
    expiresAt: '',
  };

  // ── Revision form (both sides) ──────────────────────────────────────────
  showReviseForm = false;
  revise = {
    salary: '' as number | '',
    startDate: '',
    contractType: '' as ContractType | '',
    message: '',
  };

  ngOnInit(): void {
    this.load();
    // Sensible defaults for the create form.
    const start = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000);
    this.form.startDate = this.toDateStr(start);
    const expires = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
    this.form.expiresAt = this.toDatetimeLocal(expires);

    // Reload the offer state instantly when the OTHER party (or another tab)
    // changes it - covers the recruiter's "Send offer" landing on the
    // candidate's screen without a page refresh, and the candidate's accept/
    // decline/counter landing on the recruiter's screen.
    this.wsSub = this.socket.offerChanged$.subscribe(ev => {
      if (ev?.applicationId && ev.applicationId !== this.applicationId) return;
      this.load();
    });
  }

  ngOnDestroy(): void {
    this.wsSub?.unsubscribe();
  }

  private load(): void {
    this.loading = true;
    this.offerService.get(this.applicationId).subscribe({
      next: (offer) => {
        // Backend returns 200 OK with null body when no offer exists yet.
        this.offer = offer || null;
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.error = err?.error?.message || 'Failed to load offer.';
      },
    });
  }

  // ── Computed flags ──────────────────────────────────────────────────────
  get isActive(): boolean {
    return !!this.offer && (this.offer.status === 'SENT' || this.offer.status === 'NEGOTIATING');
  }

  get statusLabel(): string {
    const s = this.offer?.status;
    switch (s) {
      case 'SENT': return 'Sent - waiting for the candidate';
      case 'NEGOTIATING': return 'Negotiating';
      case 'ACCEPTED': return 'Accepted ✓';
      case 'DECLINED': return 'Declined';
      case 'EXPIRED': return 'Expired';
      case 'WITHDRAWN': return 'Withdrawn';
      default: return '';
    }
  }

  expiresLabel(o: OfferDto): string {
    const ms = new Date(o.expiresAt).getTime() - Date.now();
    if (ms <= 0) return 'expired';
    const h = Math.floor(ms / 3_600_000);
    if (h < 24) return `${h}h left`;
    return `${Math.floor(h / 24)}d left`;
  }

  // ── Create offer (recruiter) ────────────────────────────────────────────
  openCreateForm(): void { this.showCreateForm = true; this.error = null; }
  cancelCreateForm(): void { this.showCreateForm = false; }

  sendOffer(): void {
    if (this.saving) return;
    if (!this.form.salary || +this.form.salary <= 0) {
      this.error = 'Salary must be a positive amount.'; return;
    }
    if (!this.form.startDate) { this.error = 'Start date is required.'; return; }
    if (!this.form.expiresAt) { this.error = 'Expiry date is required.'; return; }

    const req: CreateOfferRequest = {
      salary: +this.form.salary,
      currency: this.form.currency || 'TND',
      startDate: this.form.startDate,
      contractType: this.form.contractType,
      message: this.form.message?.trim() || undefined,
      expiresAt: new Date(this.form.expiresAt).toISOString(),
    };
    this.saving = true;
    this.error = null;
    this.fieldErrors = {};
    this.offerService.create(this.applicationId, req).subscribe({
      next: (offer) => {
        this.offer = offer;
        this.saving = false;
        this.showCreateForm = false;
        this.changed.emit(offer);
      },
      error: (err) => {
        this.saving = false;
        const httpError = normalizeHttpError(err);
        this.error = httpError.message || 'Failed to send offer.';
        this.fieldErrors = httpError.fieldErrors;
      },
    });
  }

  // ── Revise (counter-propose) ────────────────────────────────────────────
  openReviseForm(): void {
    if (!this.offer) return;
    // Pre-fill with current terms so changing one field is easy.
    this.revise.salary = this.offer.salary;
    this.revise.startDate = this.offer.startDate;
    this.revise.contractType = this.offer.contractType;
    this.revise.message = '';
    this.showReviseForm = true;
    this.error = null;
  }

  cancelReviseForm(): void {
    this.showReviseForm = false;
  }

  postRevision(): void {
    if (!this.offer || this.saving) return;
    if (!this.revise.message?.trim()) {
      this.error = 'Please include a short message explaining your counter.'; return;
    }
    const req: CreateRevisionRequest = {
      message: this.revise.message.trim(),
      salary: this.revise.salary ? +this.revise.salary : undefined,
      startDate: this.revise.startDate || undefined,
      contractType: this.revise.contractType || undefined,
    };
    this.saving = true;
    this.error = null;
    this.fieldErrors = {};
    this.offerService.revise(this.applicationId, req).subscribe({
      next: (offer) => {
        this.offer = offer;
        this.saving = false;
        this.showReviseForm = false;
        this.changed.emit(offer);
      },
      error: (err) => {
        this.saving = false;
        const httpError = normalizeHttpError(err);
        this.error = httpError.message || 'Failed to post revision.';
        this.fieldErrors = httpError.fieldErrors;
      },
    });
  }

  // ── Accept / Decline / Withdraw ─────────────────────────────────────────
  acceptOffer(): void {
    if (!this.offer || this.saving) return;
    Swal.fire({
      title: 'Accept this offer?',
      html: `<p style="color:rgba(255,255,255,0.75);font-size:.9rem">
        You're accepting <strong>${this.offer.salary} ${this.offer.currency}</strong>
        starting <strong>${this.offer.startDate}</strong>
        as <strong>${this.offer.contractType}</strong>. Your application moves to <strong>HIRED</strong>.
      </p>`,
      icon: 'question',
      showCancelButton: true,
      confirmButtonText: 'Yes, accept',
      cancelButtonText: 'Not yet',
      background: '#141c3c',
      color: '#e8f0fe',
    }).then((r) => {
      if (!r.isConfirmed) return;
      this.saving = true;
      this.offerService.accept(this.applicationId).subscribe({
        next: (offer) => {
          this.offer = offer;
          this.saving = false;
          this.changed.emit(offer);
          Swal.fire({ icon: 'success', title: 'Congratulations!',
                       text: 'You\'ve been hired.', timer: 2500, showConfirmButton: false });
        },
        error: (err) => {
          this.saving = false;
          this.error = err?.error?.message || 'Failed to accept offer.';
        },
      });
    });
  }

  async declineOffer(): Promise<void> {
    if (!this.offer || this.saving) return;
    const result = await Swal.fire({
      title: 'Decline this offer?',
      input: 'textarea',
      inputLabel: 'Reason (optional)',
      inputPlaceholder: 'Let the recruiter know why',
      inputAttributes: { 'aria-label': 'Reason' },
      showCancelButton: true,
      confirmButtonText: 'Decline',
      cancelButtonText: 'Cancel',
      confirmButtonColor: '#d32f2f',
      background: '#141c3c',
      color: '#e8f0fe',
    });
    if (!result.isConfirmed) return;
    this.saving = true;
    this.offerService.decline(this.applicationId, result.value || undefined).subscribe({
      next: (offer) => {
        this.offer = offer; this.saving = false; this.changed.emit(offer);
      },
      error: (err) => {
        this.saving = false;
        this.error = err?.error?.message || 'Failed to decline offer.';
      },
    });
  }

  withdrawOffer(): void {
    if (!this.offer || this.saving) return;
    Swal.fire({
      title: 'Withdraw this offer?',
      text: 'The candidate will no longer see it. You can send a new one afterwards.',
      icon: 'warning',
      showCancelButton: true,
      confirmButtonText: 'Yes, withdraw',
      cancelButtonText: 'Keep it',
      confirmButtonColor: '#dc2626',
      background: '#141c3c',
      color: '#e8f0fe',
    }).then((r) => {
      if (!r.isConfirmed) return;
      this.saving = true;
      this.offerService.withdraw(this.applicationId).subscribe({
        next: (offer) => {
          this.offer = offer; this.saving = false; this.changed.emit(offer);
        },
        error: (err) => {
          this.saving = false;
          this.error = err?.error?.message || 'Failed to withdraw offer.';
        },
      });
    });
  }

  // ── Helpers ─────────────────────────────────────────────────────────────
  private toDateStr(d: Date): string {
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  }
  private toDatetimeLocal(d: Date): string {
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
           `T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }
}

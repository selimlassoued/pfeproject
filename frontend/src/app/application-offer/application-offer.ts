import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ApplicationService } from '../services/application.service';
import { ApplicationDto } from '../model/application.dto';
import { OfferPanel } from '../offer-panel/offer-panel';
import { SeenTrackerService } from '../services/seen-tracker.service';
import { NotificationSocketService } from '../services/notification-socket.service';
import { Subscription } from 'rxjs';

/**
 * Dedicated page for managing the offer attached to an application: create,
 * revise, withdraw, and the negotiation thread. The OfferPanel component does
 * all the work; this page just provides the breadcrumb, page header, and the
 * recruiter-context props it needs.
 */
@Component({
  selector: 'app-application-offer',
  imports: [CommonModule, RouterLink, OfferPanel],
  templateUrl: './application-offer.html',
  styleUrl: './application-offer.css',
})
export class ApplicationOfferPage implements OnInit {
  private wsSub?: Subscription;
  app: ApplicationDto | null = null;
  loading = false;
  error: string | null = null;

  constructor(
    private route: ActivatedRoute,
    private appService: ApplicationService,
    private seen: SeenTrackerService,
    private socket: NotificationSocketService,
  ) {}

  ngOnDestroy(): void { this.wsSub?.unsubscribe(); }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) { this.error = 'Missing application id'; return; }
    this.loading = true;
    this.appService.getOne(id).subscribe({
      next: (data) => {
        this.app = data;
        this.loading = false;
        // Visiting the page acknowledges the NEW badge on the offer summary card.
        this.seen.markSeen(data.applicationId, 'offer');
      },
      error: (err) => {
        this.error = err?.error?.message || 'Failed to load application';
        this.loading = false;
      },
    });

    // While on the page, any incoming offer push should keep "seen" current so
    // the user doesn't see a NEW badge for activity they're literally watching.
    this.wsSub = this.socket.offerChanged$.subscribe(ev => {
      if (!this.app?.applicationId) return;
      if (ev?.applicationId && ev.applicationId !== this.app.applicationId) return;
      this.seen.markSeen(this.app.applicationId, 'offer');
    });
  }

  /** OfferPanel is allowed to create a new offer only past the interview phase. */
  get canMakeOffer(): boolean {
    const s = this.app?.status;
    return s === 'INTERVIEW_PHASE' || s === 'OFFER';
  }

  /** Refresh the application after the offer transitions so we keep the status
   *  banner accurate (e.g. HIRED after Accept). */
  onOfferChanged(): void {
    if (!this.app?.applicationId) return;
    this.appService.getOne(this.app.applicationId).subscribe({
      next: (data) => { this.app = data; },
      error: () => {},
    });
  }
}

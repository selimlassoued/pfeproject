import { Component, OnInit, OnDestroy, inject, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { NotificationService } from '../services/notification.service';
import { Notification } from '../model/notification.model';
import { Subscription } from 'rxjs';
import { NotificationSocketService } from '../services/notification-socket.service';

@Component({
  selector: 'app-notification-menu',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './notification-menu.html',
  styleUrl: './notification-menu.css',
})
export class NotificationsMenu implements OnInit, OnDestroy {
  private readonly notifService = inject(NotificationService);
  private readonly socket       = inject(NotificationSocketService);
  private readonly router       = inject(Router);

  notifications: Notification[] = [];
  unreadCount   = 0;
  open          = false;
  selectedNotif: Notification | null = null;

  private socketSub?: Subscription;

  ngOnInit(): void {
    this.load();
    this.socketSub = this.socket.notifications$.subscribe(n => {
      const idx = this.notifications.findIndex(x => x.id === n.id);
      if (idx >= 0) {
        this.notifications[idx] = n;
        this.notifications = [...this.notifications];
        // refresh detail card if it's the same notification
        if (this.selectedNotif?.id === n.id) this.selectedNotif = n;
      } else {
        this.notifications = [n, ...this.notifications];
      }
      this.unreadCount = this.notifications.filter(x => !x.read).length;
    });
  }

  ngOnDestroy(): void { this.socketSub?.unsubscribe(); }

  load(): void {
    this.notifService.getMyNotifications(0, 10000).subscribe(page => {
      this.notifications = page.content;
      this.unreadCount   = this.notifications.filter(n => !n.read).length;
    });
  }

  toggle(): void {
    this.open = !this.open;
    if (!this.open) this.selectedNotif = null;
  }

  close(): void {
    this.open = false;
    this.selectedNotif = null;
  }

  closeDetail(): void { this.selectedNotif = null; }

  selectNotif(n: Notification): void {
    // toggle: clicking the same one closes the detail
    if (this.selectedNotif?.id === n.id) {
      this.selectedNotif = null;
    } else {
      this.selectedNotif = n;
      if (!n.read) this.markRead(n);
    }
  }

  markRead(n: Notification): void {
    if (n.read) return;
    this.notifService.markRead(n.id).subscribe(() => {
      n.read = true;
      this.unreadCount = this.notifications.filter(x => !x.read).length;
    });
  }

  markAllRead(): void {
    this.notifService.markAllRead().subscribe(() => {
      this.notifications.forEach(n => (n.read = true));
      this.unreadCount = 0;
    });
  }

  /** Every notification type maps to a page, so the action button always shows. */
  hasTarget(_n: Notification): boolean {
    return true;
  }

  /** Button label, tailored to what the notification is about. */
  ctaLabel(n: Notification): string {
    switch (n.type) {
      case 'INTERVIEW_INVITE':
      case 'INTERVIEW_JOIN_REQUEST':    return 'Go to the interview';
      case 'INTERVIEW_PROPOSAL_DECLINED': return 'Propose new times';
      case 'INTERVIEW_PROPOSAL_SENT':    return 'Pick a time';
      case 'INTERVIEW_RESCHEDULE_PROPOSED': return 'Review the request';
      case 'INTERVIEW_RESCHEDULE_DECLINED': return 'Open the application';
      case 'INTERVIEW_RESCHEDULE_CANCELLED': return 'Open the application';
      case 'INTERVIEW_DELEGATION_REQUESTED': return 'Accept or decline';
      case 'INTERVIEW_DELEGATION_DECLINED': return 'Open the application';
      case 'INTERVIEW_DELEGATION_CANCELLED': return 'Open the application';
      case 'JOB_CLOSED':                 return 'Browse open positions';
      case 'CANDIDATE_FLAGGED':          return 'Review the candidate';
      case 'APPLICATION_STATUS_UPDATE': return 'View my application';
      case 'JOB_UPDATED':
      case 'JOB_QUOTA_REACHED':         return 'View the job';
      case 'OFFER_SENT':                return 'Review the offer';
      case 'OFFER_REVISED':             return 'See the latest terms';
      case 'OFFER_ACCEPTED':            return 'Open the application';
      case 'OFFER_DECLINED':            return 'Open the application';
      case 'OFFER_WITHDRAWN':           return 'Open the application';
      case 'USER_BLOCK':
      case 'USER_UNBLOCK':
      case 'ROLE_UPDATE':               return 'Go to my profile';
      default:                          return 'Open';
    }
  }

  /** Navigate to the page the notification is about, then close the menu. */
  openTarget(n: Notification): void {
    const id = n.relatedEntityId;
    let path: unknown[];
    switch (n.type) {
      case 'INTERVIEW_INVITE':
      case 'INTERVIEW_JOIN_REQUEST':
        path = id ? ['/application', id] : ['/calendar'];
        break;
      case 'INTERVIEW_PROPOSAL_DECLINED':
      case 'INTERVIEW_RESCHEDULE_PROPOSED':
      case 'INTERVIEW_RESCHEDULE_DECLINED':
      case 'INTERVIEW_RESCHEDULE_CANCELLED':
      case 'INTERVIEW_DELEGATION_REQUESTED':
      case 'INTERVIEW_DELEGATION_DECLINED':
      case 'INTERVIEW_DELEGATION_CANCELLED':
      case 'OFFER_ACCEPTED':
      case 'OFFER_DECLINED':
        // Recruiter notifications - relatedEntityId is the applicationId; take
        // them straight to that application to act.
        path = id ? ['/application', id] : ['/listApplications'];
        break;
      case 'JOB_CLOSED':
        // Candidate side - browse for similar postings.
        path = ['/browse'];
        break;
      case 'CANDIDATE_FLAGGED':
        // Confirmation receipt for the flagger - jump to the candidate page.
        path = id ? ['/users', id] : ['/listUsers'];
        break;
      case 'INTERVIEW_PROPOSAL_SENT':
      case 'OFFER_SENT':
      case 'OFFER_WITHDRAWN':
        // Candidate notifications - take them to their application view.
        path = id ? ['/my-application', id] : ['/my-applications'];
        break;
      case 'OFFER_REVISED':
        // Either side might land here - prefer candidate path; recruiter UI
        // also handles this URL via the role guard.
        path = id ? ['/my-application', id] : ['/my-applications'];
        break;
      case 'APPLICATION_STATUS_UPDATE':
        path = id ? ['/my-application', id] : ['/my-applications'];
        break;
      case 'JOB_UPDATED':
      case 'JOB_QUOTA_REACHED':
        path = id ? ['/jobs', id] : ['/browse'];
        break;
      case 'USER_BLOCK':
      case 'USER_UNBLOCK':
      case 'ROLE_UPDATE':
        path = ['/profile'];
        break;
      default:
        path = ['/'];
    }
    this.close();
    this.router.navigate(path);
  }

  /* ── helpers ── */
  getTypeIcon(type: string): string {
    switch (type) {
      case 'USER_BLOCK':                return '';
      case 'USER_UNBLOCK':              return '';
      case 'APPLICATION_STATUS_UPDATE': return '';
      case 'JOB_UPDATED':               return '';
      case 'ROLE_UPDATE':               return '';
      default:                          return '';
    }
  }

  /** Map every notification type to one of a small set of visual categories.
   *  Used by the template to pick the right SVG icon and the CSS to apply
   *  the right tinted background per category (sky / indigo / emerald / red
   *  / violet etc. — same palette as the dashboard). */
  categoryOf(type: string): 'interview' | 'job' | 'application' | 'offer' | 'user-block' | 'user-unblock' | 'role' | 'default' {
    if (!type) return 'default';
    if (type.startsWith('INTERVIEW_')) return 'interview';
    if (type.startsWith('JOB_'))       return 'job';
    if (type.startsWith('OFFER_'))     return 'offer';
    if (type === 'APPLICATION_STATUS_UPDATE') return 'application';
    if (type === 'USER_BLOCK')   return 'user-block';
    if (type === 'USER_UNBLOCK') return 'user-unblock';
    if (type === 'ROLE_UPDATE')  return 'role';
    return 'default';
  }

  getTypeLabel(type: string): string {
    switch (type) {
      case 'USER_BLOCK':                return 'Account Blocked';
      case 'USER_UNBLOCK':              return 'Account Unblocked';
      case 'APPLICATION_STATUS_UPDATE': return 'Application Update';
      case 'JOB_UPDATED':               return 'Job Updated';
      case 'JOB_QUOTA_REACHED':         return 'Job Quota Reached';
      case 'ROLE_UPDATE':               return 'Role Updated';
      case 'INTERVIEW_INVITE':          return 'Interview Invitation';
      case 'INTERVIEW_JOIN_REQUEST':    return 'Join Request';
      case 'INTERVIEW_PROPOSAL_SENT':    return 'Proposed Times';
      case 'INTERVIEW_PROPOSAL_DECLINED': return 'Proposal Declined';
      case 'INTERVIEW_RESCHEDULE_PROPOSED': return 'Reschedule Request';
      case 'INTERVIEW_RESCHEDULE_DECLINED': return 'Reschedule Declined';
      case 'INTERVIEW_RESCHEDULE_CANCELLED': return 'Reschedule Cancelled';
      case 'INTERVIEW_DELEGATION_REQUESTED': return 'Delegation Request';
      case 'INTERVIEW_DELEGATION_DECLINED': return 'Delegation Declined';
      case 'INTERVIEW_DELEGATION_CANCELLED': return 'Delegation Cancelled';
      case 'JOB_CLOSED':                return 'Job Closed';
      case 'CANDIDATE_FLAGGED':         return 'Flag Recorded';
      case 'OFFER_SENT':                return 'New Offer';
      case 'OFFER_REVISED':             return 'Offer Revised';
      case 'OFFER_ACCEPTED':            return 'Offer Accepted';
      case 'OFFER_DECLINED':            return 'Offer Declined';
      case 'OFFER_WITHDRAWN':           return 'Offer Withdrawn';
      default:                          return type;
    }
  }

  timeAgo(d: string): string {
    const diff = Date.now() - new Date(d).getTime();
    const m = Math.floor(diff / 60000);
    if (m < 1)  return 'just now';
    if (m < 60) return `${m}m ago`;
    const h = Math.floor(m / 60);
    if (h < 24) return `${h}h ago`;
    return `${Math.floor(h / 24)}d ago`;
  }

  formatDate(d: string): string {
    return new Date(d).toLocaleString('en-GB', {
      day: '2-digit', month: 'short', year: 'numeric',
      hour: '2-digit', minute: '2-digit',
    });
  }
}
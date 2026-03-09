import { Component, OnInit, OnDestroy, inject, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
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

  /* ── helpers ── */
  getTypeIcon(type: string): string {
    switch (type) {
      case 'USER_BLOCK':                return '🚫';
      case 'USER_UNBLOCK':              return '✅';
      case 'APPLICATION_STATUS_UPDATE': return '📋';
      case 'JOB_UPDATED':               return '💼';
      case 'ROLE_UPDATE':               return '🔑';
      default:                          return '🔔';
    }
  }

  getTypeLabel(type: string): string {
    switch (type) {
      case 'USER_BLOCK':                return 'Account Blocked';
      case 'USER_UNBLOCK':              return 'Account Unblocked';
      case 'APPLICATION_STATUS_UPDATE': return 'Application Update';
      case 'JOB_UPDATED':               return 'Job Updated';
      case 'ROLE_UPDATE':               return 'Role Updated';
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
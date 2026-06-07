import { Injectable, inject } from '@angular/core';
import { Client, IMessage, IFrame } from '@stomp/stompjs';
import { Subject } from 'rxjs';
import { Notification } from '../model/notification.model';
import Keycloak from 'keycloak-js';

/** Backend pushes thin "list changed" pings on /topic/interviews.list.{userId}
 *  every time something happens that could affect this user's interview set
 *  (scheduled, cancelled, rescheduled, proposal picked, delegation accepted,
 *  invited). Components listen and re-fetch. NOT a Notification (no bell entry). */
export interface InterviewListChange {
  type: 'LIST_CHANGED';
  reason: string;
  interviewId?: string;
}

/** Backend pushes a tiny "offer changed" ping on /topic/offers.{userId} every
 *  time an offer is created, revised, accepted, declined, withdrawn, or
 *  expired. Components reload their offer data on this signal. */
export interface OfferChange {
  type: 'OFFER_CHANGED';
  applicationId: string;
  offerId: string;
  status: string;
}

@Injectable({ providedIn: 'root' })
export class NotificationSocketService {
  private client: Client;
  private notif$ = new Subject<Notification>();
  private interviewList$ = new Subject<InterviewListChange>();
  private offer$ = new Subject<OfferChange>();
  private readonly keycloak = inject(Keycloak);
  private subscribed = false;

  notifications$ = this.notif$.asObservable();
  /** Wakes up the navbar imminent-interview widget (and anything else listening)
   *  when the user's interview set changed elsewhere. */
  interviewListChanged$ = this.interviewList$.asObservable();
  /** Wakes up the offer panel / offer summary card when the offer changes
   *  (created, revised, accepted, declined, withdrawn, expired). */
  offerChanged$ = this.offer$.asObservable();

  constructor() {
    // Use a webSocketFactory (instead of a fixed brokerURL string) so every
    // reconnect reads the CURRENT keycloak.token. The token is appended as
    // ?access_token= and a gateway filter promotes it to an Authorization
    // Bearer header before Spring Security runs. Reading the token lazily
    // here means refreshes - both background refreshes by keycloak-js and
    // a fresh login - are picked up automatically on the next reconnect.
    this.client = new Client({
      webSocketFactory: () => {
        const token = (this.keycloak as any)?.token ?? '';
        const sep = token ? `?access_token=${encodeURIComponent(token)}` : '';
        return new WebSocket(`ws://localhost:8888/ws/notifications${sep}`);
      },
      reconnectDelay: 5000,
    });

    this.client.onConnect = () => {
      this.subscribeWhenUserIdAvailable();
    };

    this.client.onStompError = (frame: IFrame) => {
      console.error('STOMP error', frame.headers['message'], frame.body);
    };

    this.client.onWebSocketError = (evt: Event) => {
      console.error('WebSocket error', evt);
    };

    this.client.activate();
  }

  private subscribeWhenUserIdAvailable(retries = 30): void {
    if (this.subscribed) return;

    const userId = (this.keycloak as any)?.tokenParsed?.sub;
    if (!userId) {
      if (retries <= 0) {
        console.warn('NotificationSocketService: could not resolve userId (sub); no WS notifications');
        return;
      }
      setTimeout(() => this.subscribeWhenUserIdAvailable(retries - 1), 500);
      return;
    }

    this.subscribed = true;
    this.client.subscribe(`/topic/notifications.${userId}`, (msg: IMessage) => {
      const payload = JSON.parse(msg.body) as Notification;
      this.notif$.next(payload);
    });
    this.client.subscribe(`/topic/interviews.list.${userId}`, (msg: IMessage) => {
      try {
        const payload = JSON.parse(msg.body) as InterviewListChange;
        this.interviewList$.next(payload);
      } catch {
        // tolerate non-JSON pings; the timing signal is what callers care about
        this.interviewList$.next({ type: 'LIST_CHANGED', reason: 'UNKNOWN' });
      }
    });
    this.client.subscribe(`/topic/offers.${userId}`, (msg: IMessage) => {
      try {
        const payload = JSON.parse(msg.body) as OfferChange;
        this.offer$.next(payload);
      } catch {
        this.offer$.next({
          type: 'OFFER_CHANGED', applicationId: '', offerId: '', status: '',
        });
      }
    });
  }

  disconnect(): void {
    if (this.client.active) {
      this.client.deactivate();
    }
  }
}
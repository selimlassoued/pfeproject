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
    // Bearer header before Spring Security runs.
    //
    // CRITICAL: the access token Keycloak issues is short-lived (~5 min). If
    // the WebSocket dropped and the reconnect loop reads keycloak.token, it
    // would get whatever Keycloak last fetched, which is usually stale after
    // 5 minutes idle. HTTP requests are fine because the HTTP interceptor
    // refreshes per-request, but the WS path skips that interceptor entirely.
    //
    // So we refresh in beforeConnect: keycloak.updateToken(60) refreshes
    // when the token has < 60 seconds of life remaining (or is already
    // expired - it uses the refresh_token to get a new access_token). The
    // factory below then reads the freshly-set keycloak.token value.
    //
    // We also stamp the token into the STOMP CONNECT frame as a connectHeader
    // so the notification-microservice StompAuthInterceptor (which reads
    // accessor.getFirstNativeHeader("Authorization") on CONNECT) can stamp
    // the JWT subject onto the session principal even if the upgrade-time
    // Authorization header gets dropped by the gateway proxy.
    this.client = new Client({
      beforeConnect: async () => {
        console.info('[notif-ws] beforeConnect: refreshing token...');
        try {
          const refreshed = await (this.keycloak as any).updateToken(60);
          console.info('[notif-ws] token refresh result, refreshed=' + refreshed);
        } catch (e) {
          // Refresh failed (refresh_token also expired, network down, etc.).
          // Let the connect attempt go through with whatever we have - the
          // gateway will return 401 if the token is invalid and the user
          // will be redirected to re-login on the next navigation.
          console.warn('[notif-ws] updateToken threw - proceeding anyway', e);
        }
        const token = (this.keycloak as any)?.token ?? '';
        console.info('[notif-ws] post-refresh token length=' + token.length
                   + ' parsed_exp=' + (this.keycloak as any)?.tokenParsed?.exp);
        this.client.connectHeaders = token
          ? { Authorization: `Bearer ${token}` }
          : {};
      },
      webSocketFactory: () => {
        const token = (this.keycloak as any)?.token ?? '';
        const sep = token ? `?access_token=${encodeURIComponent(token)}` : '';
        const url = `ws://localhost:8888/ws/notifications${sep}`;
        console.info('[notif-ws] opening WebSocket, url length=' + url.length);
        const ws = new WebSocket(url);
        ws.addEventListener('open',  () => console.info('[notif-ws] WS open'));
        ws.addEventListener('close', (e) => console.warn('[notif-ws] WS close', e.code, e.reason, 'clean=' + e.wasClean));
        ws.addEventListener('error', () => console.warn('[notif-ws] WS error event'));
        return ws;
      },
      reconnectDelay: 5000,
    });

    this.client.onConnect = () => {
      console.info('[notif-ws] STOMP CONNECTED frame received');
      this.subscribeWhenUserIdAvailable();
    };

    this.client.onStompError = (frame: IFrame) => {
      console.error('[notif-ws] STOMP error', frame.headers['message'], frame.body);
    };

    this.client.onWebSocketError = (evt: Event) => {
      console.error('[notif-ws] @stomp WebSocket error', evt);
    };

    this.client.onWebSocketClose = (evt: CloseEvent) => {
      console.warn('[notif-ws] @stomp WebSocket close', evt.code, evt.reason, 'clean=' + evt.wasClean);
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
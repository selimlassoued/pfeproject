import { Injectable, inject } from '@angular/core';
import { Client, IMessage, IFrame } from '@stomp/stompjs';
import { Subject } from 'rxjs';
import { Notification } from '../model/notification.model';
import Keycloak from 'keycloak-js';

@Injectable({ providedIn: 'root' })
export class NotificationSocketService {
  private client: Client;
  private notif$ = new Subject<Notification>();
  private readonly keycloak = inject(Keycloak);
  private subscribed = false;

  notifications$ = this.notif$.asObservable();

  constructor() {
    this.client = new Client({
      brokerURL: 'ws://localhost:8888/ws/notifications',
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
  }

  disconnect(): void {
    if (this.client.active) {
      this.client.deactivate();
    }
  }
}
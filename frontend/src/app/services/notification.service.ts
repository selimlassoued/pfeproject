import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Notification } from '../model/notification.model';

export interface NotificationPage {
  content: Notification[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly API_URL = 'http://localhost:8888/api/notifications';

  constructor(private http: HttpClient) {}

  getMyNotifications(page = 0, size = 10): Observable<NotificationPage> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<NotificationPage>(`${this.API_URL}/me`, { params });
  }

  markRead(id: string): Observable<void> {
    return this.http.patch<void>(`${this.API_URL}/${id}/read`, {});
  }

  markAllRead(): Observable<void> {
    return this.http.patch<void>(`${this.API_URL}/me/read-all`, {});
  }
}
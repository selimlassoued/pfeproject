import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { RouterModule } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AdminUserRow } from '../model/admin_users.type';

export interface RecruiterStat {
  userId: string;
  name: string;
  email: string;
  total: number;
  hue: number;
  initials: string;
}
@Component({
  selector: 'app-recruiter-activity',
  imports: [CommonModule, RouterModule],
  templateUrl: './recruiter-activity.html',
  styleUrl: './recruiter-activity.css',
})
export class RecruiterActivity implements OnInit {
  private API = 'http://localhost:8888';

  stats: RecruiterStat[] = [];
  loading = true;
  selected: RecruiterStat | null = null;

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.load();
  }

  private get headers(): HttpHeaders {
    const token = localStorage.getItem('access_token') || '';
    return new HttpHeaders({ Authorization: `Bearer ${token}` });
  }

  load(): void {
    forkJoin({
      activity: this.http
        .get<Record<string, number>>(`${this.API}/api/audit/recruiter-activity`, { headers: this.headers })
        .pipe(catchError(() => of<Record<string, number>>({}))),
      users: this.http
        .get<{ content: AdminUserRow[] }>(`${this.API}/api/admin/users/paged?page=0&size=100`, { headers: this.headers })
        .pipe(catchError(() => of(null))),
    }).subscribe(({ activity, users }) => {
      const userMap: Record<string, AdminUserRow> = {};
      for (const u of users?.content || []) {
        userMap[u.id] = u;
      }
      this.buildStats(activity, userMap);
      this.loading = false;
    });
  }

  private buildStats(
    activity: Record<string, number>,
    userMap: Record<string, AdminUserRow>
  ): void {
    this.stats = Object.entries(activity)
      .map(([userId, total]) => {
        const user = userMap[userId];
        const firstName = user?.firstName || '';
        const lastName  = user?.lastName  || '';
        const initials  = (firstName[0] || userId[0] || '?').toUpperCase()
                        + (lastName[0]  || '').toUpperCase();
        return {
          userId,
          name:  user ? `${firstName} ${lastName}`.trim() : userId.slice(0, 12) + '…',
          email: user?.email || '—',
          total,
          hue:     (userId.charCodeAt(0) || 60) * 37 % 360,
          initials: initials || '?',
        } as RecruiterStat;
      })
      .sort((a, b) => b.total - a.total);
  }

  get totalActions(): number {
    return this.stats.reduce((sum, s) => sum + s.total, 0);
  }

  get maxTotal(): number {
    return this.stats[0]?.total || 1;
  }

  selectUser(stat: RecruiterStat): void {
    this.selected = this.selected?.userId === stat.userId ? null : stat;
  }

  trackById(_: number, item: RecruiterStat): string {
    return item.userId;
  }
}
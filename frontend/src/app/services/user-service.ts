import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AdminUserRow } from '../model/admin_users.type';
import { PageResponse } from '../model/page-response';

@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly baseUrl = 'http://localhost:8888/api/admin';

  constructor(private http: HttpClient) {}

  async listUsers(opts?: {
    first?: number;
    max?: number;
    search?: string;
  }): Promise<AdminUserRow[]> {
    const first = opts?.first ?? 0;
    const max = opts?.max ?? 20;
    const search = (opts?.search ?? '').trim();

    let params = new HttpParams()
      .set('first', String(first))
      .set('max', String(max));

    if (search) params = params.set('search', search);

    const users = await firstValueFrom(
      this.http.get<AdminUserRow[]>(`${this.baseUrl}/users`, { params })
    );

    return (users ?? []).map(u => this.normalizeRow(u));
  }

  async getUser(id: string): Promise<AdminUserRow> {
    const u = await firstValueFrom(
      this.http.get<AdminUserRow>(`${this.baseUrl}/users/${id}`)
    );
    return this.normalizeRow(u);
  }

  async allowedRoles(): Promise<string[]> {
    const roles = await firstValueFrom(
      this.http.get<string[]>(`${this.baseUrl}/roles`)
    );
    return (roles ?? []).map(r => String(r).toUpperCase());
  }

  async updateRoles(id: string, roles: string[], reason?: string): Promise<void> {
    const body: { roles: string[]; reason?: string } = { roles };
    if (reason) {
      body.reason = reason;
    }
    await firstValueFrom(
      this.http.put<void>(`${this.baseUrl}/users/${id}/roles`, body)
    );
  }

  async setEnabled(id: string, enabled: boolean, reason?: string): Promise<void> {
    const url = enabled
      ? `${this.baseUrl}/users/${id}/unblock`
      : `${this.baseUrl}/users/${id}/block`;

    const body = reason ? { reason } : null;
    await firstValueFrom(this.http.put<void>(url, body));
  }

  async deleteUser(id: string): Promise<void> {
    await firstValueFrom(
      this.http.delete<void>(`${this.baseUrl}/users/${id}`)
    );
  }

  private normalizeRow(u: AdminUserRow): AdminUserRow {
    const attrs = u.attributes ?? {};

    const phoneNumber =
      attrs['phoneNumber']?.[0] ??
      attrs['phone']?.[0] ??
      attrs['mobile']?.[0];

    const roles = (u.roles ?? []).map(r => String(r).toUpperCase());

    const role =
      roles.includes('ADMIN') ? 'ADMIN' :
      roles.includes('RECRUITER') ? 'RECRUITER' :
      roles.includes('CANDIDATE') ? 'CANDIDATE' :
      roles.length ? roles[0] : '—';

    return {
      ...u,
      attributes: attrs,
      phoneNumber,
      roles,
      role
    };
  }

  async listUsersPaged(opts?: {
  page?: number;
  size?: number;
  search?: string;
}): Promise<PageResponse<AdminUserRow>> {

  const page = opts?.page ?? 0;
  const size = opts?.size ?? 20;
  const search = (opts?.search ?? '').trim();

  let params = new HttpParams()
    .set('page', String(page))
    .set('size', String(size));

  if (search) params = params.set('search', search);

  const res = await firstValueFrom(
    this.http.get<PageResponse<AdminUserRow>>(`${this.baseUrl}/users/paged`, { params })
  );

  return {
    ...res,
    content: (res?.content ?? []).map(u => this.normalizeRow(u)),
  };
}
}

import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AdminUserRow } from '../model/admin_users.type';

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

  // ✅ fetch one user by id
  async getUser(id: string): Promise<AdminUserRow> {
    const u = await firstValueFrom(
      this.http.get<AdminUserRow>(`${this.baseUrl}/users/${id}`)
    );
    return this.normalizeRow(u);
  }

  // ✅ list allowed roles (GET /api/admin/roles)
  async allowedRoles(): Promise<string[]> {
    const roles = await firstValueFrom(
      this.http.get<string[]>(`${this.baseUrl}/roles`)
    );
    return (roles ?? []).map(r => String(r).toUpperCase());
  }

  // ✅ update user roles (PUT /api/admin/users/{id}/roles)
  async updateRoles(id: string, roles: string[]): Promise<void> {
    await firstValueFrom(
      this.http.put<void>(`${this.baseUrl}/users/${id}/roles`, { roles })
    );
  }

  // ✅ block/unblock: use the endpoints you actually created
  async setEnabled(id: string, enabled: boolean): Promise<void> {
    const url = enabled
      ? `${this.baseUrl}/users/${id}/unblock`
      : `${this.baseUrl}/users/${id}/block`;

    await firstValueFrom(this.http.put<void>(url, null));
  }

  // ✅ delete user (DELETE /api/admin/users/{id})
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
}

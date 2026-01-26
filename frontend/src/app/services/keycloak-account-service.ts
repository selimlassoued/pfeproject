import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { User } from '../model/user.model';

type KCAccountProfile = {
  username?: string;
  firstName?: string;
  lastName?: string;
  email?: string;
  attributes?: Record<string, string[]>;
};

@Injectable({ providedIn: 'root' })
export class KeycloakAccountService {
  private readonly accountUrl =
    'http://localhost:8090/realms/ai-recruitment/account';

  constructor(private http: HttpClient) {}

  private getAttr(p: KCAccountProfile, key: string): string | undefined {
    return p.attributes?.[key]?.[0];
  }

  private setAttr(p: KCAccountProfile, key: string, value?: string) {
    if (!p.attributes) p.attributes = {};
    p.attributes[key] = value ? [value] : [];
  }

  async getUser(): Promise<User> {
    const p = await firstValueFrom(this.http.get<KCAccountProfile>(this.accountUrl));

    return {
      username: p.username,
      email: p.email,
      firstName: p.firstName ?? '',
      lastName: p.lastName ?? '',
      phoneNumber: this.getAttr(p, 'phoneNumber'),
    };
  }

  async updateUser(user: User): Promise<void> {
    const p = await firstValueFrom(this.http.get<KCAccountProfile>(this.accountUrl));

    p.username = user.username;
    p.firstName = user.firstName;
    p.lastName = user.lastName;
    // email intentionally NOT changed
    this.setAttr(p, 'phoneNumber', user.phoneNumber);

    await firstValueFrom(this.http.post<void>(this.accountUrl, p));
  }
}

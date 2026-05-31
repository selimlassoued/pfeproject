import { Injectable, inject } from '@angular/core';
import Keycloak from 'keycloak-js';

/**
 * Tracks "when did this user last look at this section?" in localStorage so
 * the application detail summary cards can show a NEW dot when there's data
 * the user hasn't seen yet. Keys are scoped per (userId, entityId, section) so
 * different users on the same machine don't collide.
 */
@Injectable({ providedIn: 'root' })
export class SeenTrackerService {
  private keycloak = inject(Keycloak);

  /** Read the last-seen timestamp for this section, in epoch ms. 0 if never. */
  lastSeen(entityId: string, section: string): number {
    const raw = localStorage.getItem(this.key(entityId, section));
    const n = raw ? Number(raw) : 0;
    return Number.isFinite(n) ? n : 0;
  }

  /** Mark this section as seen at the current time. */
  markSeen(entityId: string, section: string): void {
    localStorage.setItem(this.key(entityId, section), String(Date.now()));
  }

  /** Convert any ISO timestamp from the API into epoch ms for comparison. */
  asMs(iso: string | null | undefined): number {
    if (!iso) return 0;
    const t = new Date(iso).getTime();
    return Number.isFinite(t) ? t : 0;
  }

  /** True when the section's latest activity is newer than the last time the
   *  user opened it. Returns false when there's nothing to flag. */
  isNew(entityId: string, section: string, latestActivityMs: number): boolean {
    if (latestActivityMs <= 0) return false;
    return latestActivityMs > this.lastSeen(entityId, section);
  }

  private key(entityId: string, section: string): string {
    const uid = this.keycloak.subject ?? 'anon';
    return `seen:${uid}:${entityId}:${section}`;
  }
}

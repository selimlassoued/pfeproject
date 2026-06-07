import { HttpErrorResponse } from '@angular/common/http';
import { AbstractControl, FormGroup } from '@angular/forms';

/**
 * Single shape every component can rely on when an HTTP call fails,
 * regardless of which service produced the error (an MVC microservice
 * with our GlobalExceptionHandler, the reactive gateway with Spring's
 * default error renderer, or an offline browser).
 *
 * `status` is the HTTP status code (0 = network failure / offline).
 * `message` is something we can show a user without leaking framework
 * jargon. `fieldErrors` is the `details` map from our backend's
 * GlobalExceptionHandler: keys are DTO field names, values are the
 * human-readable validation message. `original` is the raw thing the
 * HttpClient threw, kept for debugging.
 */
export interface HttpError {
  status: number;
  message: string;
  fieldErrors: Record<string, string>;
  original: unknown;
}

/**
 * Normalize anything that might come out of HttpClient.subscribe({ error })
 * into an HttpError. Tolerates:
 *
 *   - Our GlobalExceptionHandler shape:
 *       { status, error, message, path, details: { field: msg } }
 *   - Spring Security / reactive gateway shape:
 *       { timestamp, status, error, path }
 *   - Plain text / HTML responses (proxy 5xx pages, etc.)
 *   - Network failure (status 0)
 *   - Anything else we throw at it from a catch block.
 */
export function normalizeHttpError(raw: unknown): HttpError {
  if (raw instanceof HttpErrorResponse) {
    const status = raw.status;
    const body: any = raw.error;

    if (status === 0) {
      return {
        status: 0,
        message: 'Could not reach the server. Check your connection and try again.',
        fieldErrors: {},
        original: raw,
      };
    }

    const message = pickMessage(body, raw, status);
    const fieldErrors: Record<string, string> =
      body && typeof body === 'object' && body.details && typeof body.details === 'object'
        ? { ...body.details }
        : {};

    return { status, message, fieldErrors, original: raw };
  }

  if (typeof raw === 'object' && raw !== null) {
    const anyErr = raw as any;
    return {
      status: anyErr.status ?? 0,
      message: anyErr.message ?? 'Something went wrong.',
      fieldErrors: anyErr.fieldErrors ?? {},
      original: raw,
    };
  }

  return {
    status: 0,
    message: typeof raw === 'string' ? raw : 'Something went wrong.',
    fieldErrors: {},
    original: raw,
  };
}

/**
 * Apply server-side validation errors to a reactive form so the
 * standard Angular controls (ngClass="ng-invalid", *ngIf=control.errors)
 * surface them next to the right input. Matching is case-insensitive
 * on the field name (the backend may use camelCase, the form may use
 * a slightly different key — we try the obvious variants).
 */
export function applyServerErrors(form: FormGroup, fieldErrors: Record<string, string>): void {
  for (const [key, message] of Object.entries(fieldErrors)) {
    const ctl = findControl(form, key);
    if (!ctl) continue;
    const existing = ctl.errors ?? {};
    ctl.setErrors({ ...existing, server: message });
    ctl.markAsTouched();
  }
}

/** Clear server-side errors before the next submit attempt. */
export function clearServerErrors(form: FormGroup): void {
  Object.values(form.controls).forEach((ctl) => {
    if (ctl.errors?.['server']) {
      const { server, ...rest } = ctl.errors;
      ctl.setErrors(Object.keys(rest).length ? rest : null);
    }
  });
}

// ── helpers ────────────────────────────────────────────────────────────

function pickMessage(body: any, raw: HttpErrorResponse, status: number): string {
  if (body && typeof body === 'object') {
    if (typeof body.message === 'string' && body.message.length) return body.message;
    if (typeof body.error === 'string' && body.error.length && body.error !== body.message) return body.error;
  }
  if (typeof body === 'string' && body.length && body.length < 300) return body;
  if (raw.statusText) return `${status} ${raw.statusText}`;
  return `Request failed with status ${status}.`;
}

function findControl(form: FormGroup, key: string): AbstractControl | null {
  const direct = form.get(key);
  if (direct) return direct;
  const lower = key.toLowerCase();
  for (const name of Object.keys(form.controls)) {
    if (name.toLowerCase() === lower) return form.get(name);
  }
  return null;
}
